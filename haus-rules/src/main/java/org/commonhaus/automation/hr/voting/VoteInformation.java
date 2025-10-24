package org.commonhaus.automation.hr.voting;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.commonhaus.automation.github.context.DataActor;
import org.commonhaus.automation.github.context.DataCommonItem;
import org.commonhaus.automation.github.context.DataPullRequestReview;
import org.commonhaus.automation.github.context.DataReaction;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.github.context.GitHubQueryContext;
import org.commonhaus.automation.github.context.GitHubTeamService;
import org.commonhaus.automation.github.context.TeamList;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.hr.AppContextService;
import org.commonhaus.automation.hr.config.VoteConfig;
import org.commonhaus.automation.hr.config.VoteConfig.AlternateConfig;
import org.commonhaus.automation.hr.config.VoteConfig.AlternateDefinition;
import org.commonhaus.automation.hr.config.VoteConfig.Threshold;
import org.commonhaus.automation.hr.voting.VoteTally.CountingMethod;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.ReactionContent;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.logging.Log;

public class VoteInformation {
    public record Alternates(int hash, Map<String, Map<String, DataActor>> alternates) {
    }

    static final Pattern groupPattern = Pattern.compile("voting group[^@]+@([\\S]+)", Pattern.CASE_INSENSITIVE);

    static final String quoted = "['\"]([^'\"]*)['\"]";
    static final Pattern votePattern = Pattern.compile("<!--vote(.*?)-->", Pattern.CASE_INSENSITIVE);
    static final Pattern okPattern = Pattern.compile("ok=" + quoted, Pattern.CASE_INSENSITIVE);
    static final Pattern approvePattern = Pattern.compile("approve=" + quoted, Pattern.CASE_INSENSITIVE);
    static final Pattern revisePattern = Pattern.compile("revise=" + quoted, Pattern.CASE_INSENSITIVE);
    static final Pattern thresholdPattern = Pattern.compile("threshold=" + quoted, Pattern.CASE_INSENSITIVE);

    private final AppContextService ctx;
    private final ScopedQueryContext qc;
    private final VoteEvent event;

    public final CountingMethod voteType;
    public final List<ReactionContent> revise;
    public final List<ReactionContent> ok;
    public final List<ReactionContent> approve;

    public final String group;
    public final Set<DataActor> teamList;
    public final Map<String, DataActor> alternates;
    public final VoteConfig.Threshold votingThreshold;
    public final DataCommonItem item;

    private List<DataPullRequestReview> prReviews;

    /**
     * Digest information about a vote, using the latest issue content
     *
     * @param item
     * @param qc
     */
    public VoteInformation(AppContextService ctx, ScopedQueryContext qc, VoteConfig voteConfig, DataCommonItem item,
            VoteEvent event) {
        this.ctx = ctx;
        this.event = event;
        this.item = item;
        this.qc = qc;

        String groupValue = null;
        Set<DataActor> teamList = null;
        Map<String, DataActor> alternates = null;

        // Test body for "Voting group" followed by a team name
        Matcher groupM = groupPattern.matcher(item.body);
        if (groupM.find()) {
            groupValue = groupM.group(1);
            TeamList list = ctx.getTeamMembershipService().getTeamList(qc, groupValue);
            if (list != null) {
                list.removeExcludedMembers(
                        a -> qc.isBot(a.login) || voteConfig.isMemberExcluded(a.login));
                teamList = list.members;
            }
            alternates = getAlternates(qc, groupValue, voteConfig);
        }
        this.group = groupValue;
        this.teamList = teamList;
        this.alternates = alternates;

        Matcher voteM = votePattern.matcher(item.body);
        String voteDefinition = voteM.find() ? voteM.group(1).toLowerCase() : null;
        if (isPullRequest()) {
            voteDefinition = "::marthas " + (voteDefinition == null ? "" : voteDefinition);
        }

        CountingMethod voteType = CountingMethod.undefined;
        Threshold votingThreshold = voteConfig.votingThreshold(this.group);
        List<ReactionContent> approve = List.of();
        List<ReactionContent> ok = List.of();
        List<ReactionContent> revise = List.of();

        if (voteDefinition != null) {
            Matcher thresholdM = thresholdPattern.matcher(voteDefinition);
            votingThreshold = thresholdM.find()
                    ? Threshold.fromString(thresholdM.group(1))
                    : voteConfig.votingThreshold(this.group);

            if (voteDefinition.startsWith("::marthas")) {
                voteType = CountingMethod.marthas;

                Matcher approveM = approvePattern.matcher(voteDefinition);
                approve = approveM.find() ? listFrom(approveM.group(1)) : List.of(ReactionContent.PLUS_ONE);

                Matcher okM = okPattern.matcher(voteDefinition);
                ok = okM.find() ? listFrom(okM.group(1)) : List.of(ReactionContent.EYES);

                Matcher reviseM = revisePattern.matcher(voteDefinition);
                revise = reviseM.find() ? listFrom(reviseM.group(1)) : List.of(ReactionContent.MINUS_ONE);
            } else if (voteDefinition.startsWith("::manual")) {
                voteType = voteDefinition.contains("comments") ? CountingMethod.manualComments : CountingMethod.manualReactions;
            }
        }

        this.voteType = voteType;
        this.votingThreshold = votingThreshold;
        this.approve = approve;
        this.ok = ok;
        this.revise = revise;
    }

    public List<DataPullRequestReview> getReviews() {
        if (!isPullRequest()) {
            return List.of();
        }
        if (prReviews == null) {
            prReviews = qc.queryReviews(event.itemNodeId);
            // The bot's reviews are not counted
            prReviews.removeIf(x -> qc.isBot(x.author.login));
        }
        return prReviews;
    }

    public boolean isPullRequest() {
        return event.eventType == EventType.pull_request
                || event.eventType == EventType.pull_request_review;
    }

    public String getTitle() {
        return item.title;
    }

    public boolean countComments() {
        return voteType == CountingMethod.manualComments;
    }

    public boolean isValid() {
        return !(invalidGroup() || invalidReactions());
    }

    public boolean invalidGroup() {
        return teamList == null || teamList.isEmpty();
    }

    public boolean invalidReactions() {
        if (this.voteType == CountingMethod.undefined) {
            // we can't count votes if the vote type is undefined
            return true;
        }
        if (this.voteType != CountingMethod.marthas) {
            // all reactions are valid if they are being counted by humans
            return false;
        }
        if (approve.isEmpty() || ok.isEmpty() || revise.isEmpty()) {
            return true;
        }
        // None of the groups should overlap
        return !Collections.disjoint(ok, approve) || !Collections.disjoint(ok, revise)
                || !Collections.disjoint(approve, revise);
    }

    public String getErrorContent() {
        // GH Markdown likes \r\n line endings
        return "Configuration for item is invalid:\r\n\r\n- Team for specified group (%s) must exist (%s)\r\n%s\r\n%s\r\n%s\r\n"
                .formatted(
                        group,
                        teamList != null,
                        invalidGroup() ? validTeamComment : "",
                        explainVoteCounting(),
                        invalidReactions() ? validReactionsComment : "");
    }

    private String explainVoteCounting() {
        return switch (voteType) {
            case marthas -> showReactionGroups();
            case manualReactions -> "- Counting reactions manually";
            case manualComments -> "- Counting comments";
            case undefined -> "- No valid vote counting method found";
        };
    }

    private String showReactionGroups() {
        String description = isPullRequest()
                ? "Counting non-empty valid reactions and review responses in the following categories:"
                : "Counting non-empty valid reactions in the following categories:";

        return "\r\n- %s:\r\n- approve: %s\r\n- ok: %s\r\n- revise: %s"
                .formatted(
                        description,
                        showReactions(approve) + (isPullRequest() ? ", PR review approved" : ""),
                        showReactions(ok) + (isPullRequest() ? ", PR review closed with comments" : ""),
                        showReactions(revise) + (isPullRequest() ? ", PR review requires changes" : ""));
    }

    private static final String validTeamComment = """
            \r
            > [!TIP]\r
            > Item description should contain text that matches the following (case-insensitive):  \r
            > `voting group[^@]+@([^ ]+)`\r
            >\r
            > For example:\r
            >\r
            > ```md\r
            > ## Voting group\r
            > @commonhaus/test-quorum-default\r
            > ```\r
            >\r
            > Or:\r
            >\r
            > ```md\r
            > - voting group: @commonhaus/test-quorum-default\r
            > ```\r
            """;

    private static final String validReactionsComment = """
            \r
            > [!TIP]\r
            > Item description should contain an HTML comment that describes how votes should be counted.\r
            >\r
            > Some examples:\r
            >\r
            > ```md\r
            > <!--vote::manual -->\r
            > <!--vote::manual comments -->\r
            > <!--vote::marthas approve="+1" ok="eyes" revise="-1" -->\r
            > <!--vote::marthas approve="+1, rocket, hooray" ok="eyes" revise="-1, confused" -->\r
            > ```\r
            >\r
            > - **manual**: The bot will group votes by reaction, and count votes of the required group\r
            > - **manual with comments**: The bot will count comments by members of the required group\r
            > - **marthas**: The bot will group votes (approve, ok, revise), and count votes of the required group\r
            >\r
            > Valid values: +1, -1, laugh, confused, heart, hooray, rocket, eyes\r
            > aliases: [+1, plus_one, thumbs_up], [-1, minus_one, thumbs_down]\r
            """;

    private String showReactions(List<ReactionContent> reactions) {
        return reactions.stream()
                .map(x -> x == null ? "invalid" : x.getContent())
                .collect(Collectors.joining(", "));
    }

    private List<ReactionContent> listFrom(String group) {
        if (group == null) {
            return List.of();
        }
        String[] groups = group.split("\\s*,\\s*");
        return Stream.of(groups)
                .map(x -> x.replace(":", ""))
                .map(DataReaction::reactionContentFrom)
                .toList();
    }

    private Map<String, DataActor> getAlternates(GitHubQueryContext qc, String teamName, VoteConfig voteConfig) {
        // Generate a cache key using the repository ID
        String key = "ALTS_" + qc.getRepositoryId();

        // Look up or compute the alternates for the given key
        Alternates alts = VoteQueryCache.ALT_ACTORS.computeIfAbsent(key, k -> {
            List<AlternateConfig> alternates = voteConfig.alternates;
            int hash = alternates == null ? 0 : alternates.hashCode();

            // If no alternates are configured, return an empty map
            if (alternates == null) {
                return new Alternates(hash, Map.of());
            }

            // Iterate over the list of alternate configurations
            Map<String, Map<String, DataActor>> githubTeamToAlternates = new HashMap<>();
            for (AlternateConfig alt : alternates) {
                if (!alt.valid()) {
                    continue;
                }

                // Retrieve the configuration data for the alternate
                Optional<JsonNode> configDataNode = getAlternateConfigData(qc, alt);
                if (configDataNode.isEmpty()) {
                    continue;
                }

                // Map logins from the primary team to the secondary team
                JsonNode data = configDataNode.get();
                for (AlternateDefinition altDef : alt.mapping()) {
                    String primaryTeam = altDef.primary().team();
                    Map<String, DataActor> loginToSecond = mapLoginToSecond(qc, data, altDef);
                    if (loginToSecond.isEmpty()) {
                        continue;
                    }
                    githubTeamToAlternates.computeIfAbsent(primaryTeam, x -> new HashMap<>()).putAll(loginToSecond);
                }
            }
            // Return the computed alternates
            return new Alternates(hash, githubTeamToAlternates);
        });

        // Return the alternates for the specified team name (if any)
        return alts.alternates().get(teamName);
    }

    private Optional<JsonNode> getAlternateConfigData(GitHubQueryContext qc, AlternateConfig altConfig) {
        GHRepository repo = qc.getRepository(altConfig.repo());
        if (repo == null) {
            Log.warnf("[%s] voteInformation.getAlternateConfigData: source repository %s not found",
                    qc.getLogId(), altConfig.repo());
            return Optional.empty();
        }
        // get contents of file from the specified repo + path
        GHContent content = qc.readSourceFile(repo, altConfig.source());
        if (content == null || qc.hasErrors()) {
            Log.warnf("[%s] voteInformation.getAlternateConfigData: source %s from %s not found",
                    qc.getLogId(), altConfig.source(), altConfig.repo());
            return Optional.empty();
        }
        Optional<JsonNode> config = Optional.ofNullable(qc.readYamlContent(content));
        if (config.isEmpty() || qc.hasErrors()) {
            qc.logAndSendContextErrors("[%s] voteInformation.getAlternateConfigData: unable to parse %s from %s"
                    .formatted(qc.getLogId(), altConfig.source(), altConfig.repo()));
            return Optional.empty();
        }
        return config;
    }

    private Map<String, DataActor> mapLoginToSecond(GitHubQueryContext qc, JsonNode data, AlternateDefinition altDef) {
        GitHubTeamService teamService = ctx.getTeamMembershipService();
        // AlternateDefinition has been checked for validity.
        // Contents of data (JsonNode) have not.
        TeamList primaryTeam = teamService.getTeamList(qc, altDef.primary().team());
        Optional<JsonNode> primaryDataNode = getValidDataNode(altDef.primary().data(), data);
        if (primaryDataNode.isEmpty()) {
            Log.warnf("[%s] voteInformation.getAlternates: primary config group (%s) or github team (%s) not found",
                    qc.getLogId(), altDef.primary().data(), altDef.primary().team());
            return Map.of();
        }

        TeamList secondaryTeam = teamService.getTeamList(qc, altDef.secondary().team());
        Optional<JsonNode> secondaryDataNode = getValidDataNode(altDef.secondary().data(), data);
        if (secondaryDataNode.isEmpty()) {
            Log.warnf("[%s] voteInformation.getAlternates: secondary config group (%s) or github team (%s) not found",
                    qc.getLogId(), altDef.secondary().data(), altDef.secondary().team());
            return Map.of();
        }

        String match = altDef.field();
        Map<String, DataActor> result = new HashMap<>();
        Map<String, String> primaryMap = fieldToLoginMap(match, primaryDataNode.get());
        Map<String, String> secondaryMap = fieldToLoginMap(match, secondaryDataNode.get());
        for (Entry<String, String> entry : primaryMap.entrySet()) {
            String primaryLogin = entry.getValue();
            String secondaryLogin = secondaryMap.get(entry.getKey());
            if (primaryLogin == null || secondaryLogin == null) {
                continue;
            }
            if (primaryTeam.hasLogin(primaryLogin)) {
                secondaryTeam.members.stream()
                        .filter(a -> a.login.equals(secondaryLogin))
                        .findFirst()
                        .ifPresent(a -> result.put(primaryLogin, a));
            }
        }
        return result;
    }

    private Optional<JsonNode> getValidDataNode(String key, JsonNode node) {
        if (key == null || key.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(node.get(key)).filter(JsonNode::isArray);
    }

    // egc:
    // - project: jbang
    //   login: maxandersen
    private Map<String, String> fieldToLoginMap(String field, JsonNode node) {
        Map<String, String> fieldToLogin = new HashMap<>();
        node.elements().forEachRemaining(x -> {
            String fieldValue = stringFrom(x, field);
            String login = stringFrom(x, "login");
            if (fieldValue != null && login != null) {
                fieldToLogin.put(fieldValue, login);
            }
        });
        return fieldToLogin;
    }

    private String stringFrom(JsonNode x, String fieldName) {
        JsonNode field = x.get(fieldName);
        return field == null ? null : field.asText();
    }
}
