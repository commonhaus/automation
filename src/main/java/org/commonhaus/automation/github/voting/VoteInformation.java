package org.commonhaus.automation.github.voting;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.commonhaus.automation.github.Voting;
import org.commonhaus.automation.github.model.DataReaction;
import org.commonhaus.automation.github.model.QueryCache;
import org.commonhaus.automation.github.model.QueryHelper.QueryContext;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHTeam;
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
    static final Pattern consensusPattern = Pattern.compile(
            "<!--vote::marthas approve=" + quoted + " ok=" + quoted + " revise=" + quoted + " -->",
            Pattern.CASE_INSENSITIVE);

    static final Pattern manualPattern = Pattern.compile("<!--vote::manual (.*?)-->", Pattern.CASE_INSENSITIVE);

    public final Type voteType;
    public final List<ReactionContent> revise;
    public final List<ReactionContent> ok;
    public final List<ReactionContent> approve;

    public final String group;
    public final GHTeam team;
    public final Voting.Threshold votingThreshold;

    public VoteInformation(VoteEvent event) {
        QueryContext qc = event.getQueryContext();
        Voting.Config voteConfig = event.getVotingConfig();
        String bodyString = event.getBody();

        // Body contains voting group? "Voting group"
        Matcher m = groupPattern.matcher(bodyString);
        GHTeam team = null;
        if (m.find()) {
            this.group = m.group(1);

            GHOrganization org = event.getOrganization();
            team = QueryCache.TEAM.getCachedValue(this.group);
            if (team == null) {
                String teamName = this.group.replace(org.getLogin() + "/", "");
                team = qc.execGitHubSync((gh, dryRun) -> org.getTeamByName(teamName));
                if (!qc.hasErrors()) {
                    QueryCache.TEAM.putCachedValue(this.group, team);
                }
            }
        } else {
            this.group = null;
        }
        this.team = team;
        this.votingThreshold = voteConfig.votingThreshold(this.group);

        m = consensusPattern.matcher(bodyString);
        if (m.find()) {
            this.voteType = Type.marthas;
            this.approve = listFrom(m.group(1));
            this.ok = listFrom(m.group(2));
            this.revise = listFrom(m.group(3));
        } else {
            m = manualPattern.matcher(bodyString);
            boolean manual = m.find();
            this.voteType = manual && m.group(1) != null && m.group(1).contains("comments")
                    ? Type.manualComments
                    : manual ? Type.manualReactions : Type.undefined;
            this.approve = List.of();
            this.ok = List.of();
            this.revise = List.of();
        }
    }

    public boolean countComments() {
        return voteType == Type.manualComments;
    }

    public boolean isValid() {
        return !(invalidGroup() || invalidReactions());
    }

    public boolean invalidGroup() {
        return team == null;
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
        return approve.isEmpty() || ok.isEmpty() || revise.isEmpty()
                || approve.contains(null) || ok.contains(null) || revise.contains(null);
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
                team != null,
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
        return String.format("""
                - Reactions must be non-empty and use valid reactions:\r
                    - approve: %s\r
                    - ok: %s\r
                    - revise: %s""",
                showReactions(approve),
                showReactions(ok),
                showReactions(revise));
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
}
