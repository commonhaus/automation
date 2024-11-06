package org.commonhaus.automation.github.voting;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.commonhaus.automation.github.context.DataPullRequestReview;
import org.commonhaus.automation.github.context.DataReaction;
import org.commonhaus.automation.github.context.QueryContext;
import org.commonhaus.automation.github.context.TeamList;
import org.commonhaus.automation.github.voting.VoteConfig.Threshold;
import org.kohsuke.github.ReactionContent;

public class VoteInformation {
    enum Type {
        marthas,
        manualReactions,
        manualComments,
        undefined
    }

    static final Pattern groupPattern = Pattern.compile("voting group[^@]+@([^\\s]+)", Pattern.CASE_INSENSITIVE);

    static final String quoted = "['\"]([^'\"]*)['\"]";
    static final Pattern votePattern = Pattern.compile("<!--vote(.*?)-->", Pattern.CASE_INSENSITIVE);
    static final Pattern okPattern = Pattern.compile("ok=" + quoted, Pattern.CASE_INSENSITIVE);
    static final Pattern approvePattern = Pattern.compile("approve=" + quoted, Pattern.CASE_INSENSITIVE);
    static final Pattern revisePattern = Pattern.compile("revise=" + quoted, Pattern.CASE_INSENSITIVE);
    static final Pattern thresholdPattern = Pattern.compile("threshold=" + quoted, Pattern.CASE_INSENSITIVE);

    private final VoteEvent event;

    public final Type voteType;
    public final List<ReactionContent> revise;
    public final List<ReactionContent> ok;
    public final List<ReactionContent> approve;

    public final String group;
    public final TeamList teamList;
    public final VoteConfig.Threshold votingThreshold;

    public VoteInformation(VoteEvent event) {
        this.event = event;

        QueryContext qc = event.getQueryContext();
        VoteConfig voteConfig = event.getVotingConfig();
        String bodyString = event.getVoteBody();
        String groupValue = null;
        TeamList teamList = null;

        // Test body for "Voting group" followed by a team name
        Matcher groupM = groupPattern.matcher(bodyString);
        if (groupM.find()) {
            groupValue = groupM.group(1);
            teamList = qc.getTeamList(groupValue);
            if (teamList != null) {
                teamList.removeExcludedMembers(
                        a -> qc.isBot(a.login) || voteConfig.isMemberExcluded(a.login));
            }
        }
        this.group = groupValue;
        this.teamList = teamList;

        Matcher voteM = votePattern.matcher(bodyString);
        String voteDefinition = voteM.find() ? voteM.group(1).toLowerCase() : null;
        if (isPullRequest()) {
            voteDefinition = "::marthas " + (voteDefinition == null ? "" : voteDefinition);
        }

        Type voteType = Type.undefined;
        Threshold votingThreshold = voteConfig.votingThreshold(this.group);
        List<ReactionContent> approve = List.of();
        List<ReactionContent> ok = List.of();
        List<ReactionContent> revise = List.of();

        if (voteDefinition == null) {
            voteType = Type.undefined;
        } else {
            Matcher thresholdM = thresholdPattern.matcher(voteDefinition);
            votingThreshold = thresholdM != null && thresholdM.find()
                    ? Threshold.fromString(thresholdM.group(1))
                    : voteConfig.votingThreshold(this.group);

            if (voteDefinition.startsWith("::marthas")) {
                voteType = Type.marthas;

                Matcher approveM = approvePattern.matcher(voteDefinition);
                approve = approveM.find() ? listFrom(approveM.group(1)) : List.of(ReactionContent.PLUS_ONE);

                Matcher okM = okPattern.matcher(voteDefinition);
                ok = okM.find() ? listFrom(okM.group(1)) : List.of(ReactionContent.EYES);

                Matcher reviseM = revisePattern.matcher(voteDefinition);
                revise = reviseM.find() ? listFrom(reviseM.group(1)) : List.of(ReactionContent.MINUS_ONE);
            } else if (voteDefinition.startsWith("::manual")) {
                voteType = voteDefinition.contains("comments") ? Type.manualComments : Type.manualReactions;
            }
        }

        this.voteType = voteType;
        this.votingThreshold = votingThreshold;
        this.approve = approve;
        this.ok = ok;
        this.revise = revise;
    }

    public Collection<DataPullRequestReview> getReviews() {
        return event.getReviews();
    }

    public boolean isPullRequest() {
        return event.isPullRequest();
    }

    public boolean countComments() {
        return voteType == Type.manualComments;
    }

    public boolean isValid() {
        return !(invalidGroup() || invalidReactions());
    }

    public boolean invalidGroup() {
        return teamList == null || teamList.isEmpty();
    }

    public boolean invalidReactions() {
        if (this.voteType == Type.undefined) {
            // we can't count votes if the vote type is undefined
            return true;
        }
        if (this.voteType != Type.marthas) {
            // all reactions are valid if they are being counted by humans
            return false;
        }
        if (approve.isEmpty() || ok.isEmpty() || revise.isEmpty()) {
            return true;
        }
        if (Collections.disjoint(ok, approve) && Collections.disjoint(ok, revise) && Collections.disjoint(approve, revise)) {
            // None of the groups overlap
            return false;
        }
        return true;
    }

    public String getErrorContent() {
        // GH Markdown likes \r\n line endings
        return String.format("""
                Configuration for item is invalid:\r
                \r
                - Team for specified group (%s) must exist (%s)\r
                %s\r
                %s\r
                %s\r
                """,
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

        return String.format("""
                - %s:\r
                    - approve: %s\r
                    - ok: %s\r
                    - revise: %s""",
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

    public String getTitle() {
        return event.getTitle();
    }
}
