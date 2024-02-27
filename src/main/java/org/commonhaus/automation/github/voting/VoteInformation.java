package org.commonhaus.automation.github.voting;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.commonhaus.automation.github.model.DataReaction;
import org.commonhaus.automation.github.model.QueryHelper.QueryCache;
import org.commonhaus.automation.github.model.QueryHelper.QueryContext;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHTeam;
import org.kohsuke.github.ReactionContent;

public class VoteInformation {
    static final Pattern groupPattern = Pattern.compile("voting group[^@]+@([^\\s]+)", Pattern.CASE_INSENSITIVE);
    static final String quoted = "['\"]([^'\"]*)['\"]";
    static final Pattern consensusPattern = Pattern.compile(
            "<!--vote::marthas approve=" + quoted + " ok=" + quoted + " revise=" + quoted + " -->",
            Pattern.CASE_INSENSITIVE);

    public final List<ReactionContent> revise;
    public final List<ReactionContent> ok;
    public final List<ReactionContent> approve;

    public final String group;
    public final GHTeam team;
    public final String teamKey;

    public VoteInformation(String bodyString, QueryContext qc) {
        // Body contains voting group? "Voting group"
        Matcher m = groupPattern.matcher(bodyString);
        GHTeam team = null;
        if (m.find()) {
            this.group = m.group(1);

            GHOrganization org = qc.getEventData().getOrganization();
            String teamName = group.replace(org.getLogin() + "/", "");
            this.teamKey = org.getLogin() + "/" + teamName;

            team = QueryCache.TEAM.getCachedValue(this.teamKey, GHTeam.class);
            if (team == null) {
                team = qc.execGitHubSync((gh, dryRun) -> {
                    return org.getTeamByName(teamName);
                });
                if (!qc.hasErrors()) {
                    QueryCache.TEAM.putCachedValue(this.teamKey, team);
                }
            }
        } else {
            this.group = null;
            this.teamKey = null;
        }
        this.team = team;

        // Body contains Consensus mechanism? "use emoji reactions"
        m = consensusPattern.matcher(bodyString);
        if (m.find()) {
            this.approve = listFrom(m.group(1));
            this.ok = listFrom(m.group(2));
            this.revise = listFrom(m.group(3));
        } else {
            this.approve = List.of();
            this.ok = List.of();
            this.revise = List.of();
        }
    }

    public boolean isValid(QueryContext qc) {
        return !(invalidGroup() || invalidReactions());
    }

    public boolean invalidGroup() {
        return team == null;
    }

    public boolean invalidReactions() {
        return approve.isEmpty() || ok.isEmpty() || revise.isEmpty()
                || approve.contains(null) || ok.contains(null) || revise.contains(null);
    }

    public String getErrorContent() {
        // GH Markdown likes \r\n line endings
        return String.format(""
                + "Configuration for item is invalid:\r\n"
                + "\r\n"
                + "- Team for specified group (%s) must exist (%s)\r\n"
                + "%s\r\n"
                + "- Reactions must be non-empty and use valid reactions:\r\n"
                + "    - approve: %s\r\n"
                + "    - ok: %s\r\n"
                + "    - revise: %s\r\n"
                + "%s\r\n",
                group,
                team != null,
                invalidGroup() ? validTeamComment() : "",
                showReactions(approve),
                showReactions(ok),
                showReactions(revise),
                invalidReactions() ? validReactionsComment() : ""); // de-indent
    }

    private String validTeamComment() {
        // GH Markdown likes \r\n line endings
        return "\r\n"
                + "> [!TIP]\r\n"
                + "> Item description should contain text that matches the following (case-insensitive):  \r\n"
                + "> `voting group[^@]+@([^ ]+)`\r\n"
                + ">\r\n"
                + "> For example:\r\n"
                + ">\r\n"
                + "> ```md\r\n"
                + "> ## Voting group\r\n"
                + "> @commonhaus/test-quorum-default\r\n"
                + "> ```\r\n"
                + ">\r\n"
                + "> Or:\r\n"
                + ">\r\n"
                + "> ```md\r\n"
                + "> - voting group: @commonhaus/test-quorum-default\r\n"
                + "> ```\r\n";
    }

    private String validReactionsComment() {
        // GH Markdown likes \r\n line endings
        // Passing this through GraphQL query will eat any other flavor of whitespace.
        return "\r\n"
                + "> [!TIP]\r\n"
                + "> Item description should contain an HTML comment that matches the following (case-insensitive):  \r\n"
                + "> `<!--vote::marthas approve=\"([^\"]*)\" ok=\"([^\"]*)\" revise=\"([^\"]*)\" -->`\r\n"
                + ">\r\n"
                + "> For example:\r\n"
                + ">\r\n"
                + "> ```md\r\n"
                + "> <!--vote::marthas approve=\"+1\" ok=\"eyes\" revise=\"-1\" -->\r\n"
                + "> ```\r\n"
                + ">\r\n"
                + "> Or:\r\n"
                + ">\r\n"
                + "> ```md\r\n"
                + "> <!--vote::marthas approve=\"+1, rocket, hooray\" ok=\"eyes\" revise=\"-1, confused\" -->\r\n"
                + "> ```\r\n"
                + ">\r\n"
                + "> Valid values: +1, -1, laugh, confused, heart, hooray, rocket, eyes\r\n"
                + "> aliases: [+1, plus_one, thumbs_up], [-1, minus_one, thumbs_down]\r\n";
    }

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
        return List.of(groups).stream()
                .map(x -> x.replace(":", ""))
                .map(x -> DataReaction.reactionContentFrom(x))
                .toList();
    }
}
