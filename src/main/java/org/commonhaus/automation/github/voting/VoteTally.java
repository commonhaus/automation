package org.commonhaus.automation.github.voting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.commonhaus.automation.github.model.DataActor;
import org.commonhaus.automation.github.model.DataReaction;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class VoteTally {

    @JsonProperty
    final String group;

    @JsonProperty
    final int groupSize;

    @JsonProperty
    final int groupVotes;

    @JsonProperty
    public final Category approve = new Category();

    @JsonProperty
    public final Category ok = new Category();

    @JsonProperty
    public final Category revise = new Category();

    public VoteTally(VoteInformation info, List<DataReaction> votes, List<String> teamLogins) {
        for (DataReaction reaction : votes) {
            DataActor user = reaction.user;
            if (info.approve.contains(reaction.reactionContent)) {
                approve.actor.add(user);
            } else if (info.ok.contains(reaction.reactionContent)) {
                ok.actor.add(user);
            } else if (info.revise.contains(reaction.reactionContent)) {
                revise.actor.add(user);
            }
        }

        // Count only most positive vote (if several, assume the best rather than the worst)
        // Make note of the duplicates so they can be listed.
        Set<DataActor> counted = new HashSet<>();

        approve.finalCount(counted, teamLogins);
        ok.finalCount(counted, teamLogins);
        revise.finalCount(counted, teamLogins);

        group = info.group;
        groupSize = teamLogins.size();
        groupVotes = approve.teamTotal + ok.teamTotal + revise.teamTotal;
    }

    public boolean hasQuorum() {
        return groupVotes >= groupSize;
    }

    public String toMarkdown() {
        List<String> duplicates = new ArrayList<>();

        approve.notCounted.forEach(actor -> duplicates.add(actor.login + " (approve)"));
        ok.notCounted.forEach(actor -> duplicates.add(actor.login + " (ok)"));
        revise.notCounted.forEach(actor -> duplicates.add(actor.login + " (revise)"));

        // GH Markdown likes \r\n line endings
        return String.format("\r\n"
                + "%s %d of %d members of @%s voted.\r\n"
                + "\r\n"
                + "| Vote | Total | Team | Voting members |\r\n"
                + "| --- | --- | --- | --- |\r\n"
                + "| Approve | %d | %d | %s |\r\n"
                + "| OK | %d | %d | %s |\r\n"
                + "| Revise | %d | %d | %s |\r\n"
                + "\r\n"
                + "%s\r\n",
                ((groupVotes >= groupSize) ? "✅" : ""), groupVotes, groupSize, group,
                approve.total, approve.teamTotal, actorsToString(approve.team),
                ok.total, ok.teamTotal, actorsToString(ok.team),
                revise.total, revise.teamTotal, actorsToString(revise.team),
                duplicates.isEmpty()
                        ? ""
                        : "The following votes were not counted (duplicates):\r\n" + String.join(", ", duplicates));
    }

    String actorsToString(List<DataActor> actors) {
        return actors.stream()
                .map(actor -> String.format("[%s](%s)", actor.login, actor.url))
                .collect(Collectors.joining(", "));
    }

    @RegisterForReflection
    public static class Category {
        @JsonProperty
        List<DataActor> actor = new ArrayList<>();
        @JsonProperty
        List<DataActor> team = new ArrayList<>();
        @JsonProperty
        List<DataActor> notCounted = new ArrayList<>();

        @JsonProperty
        int teamTotal = 0;
        @JsonProperty
        int total = 0;

        void finalCount(Set<DataActor> seenLogins, List<String> teamLogins) {
            for (DataActor actor : this.actor) {
                if (seenLogins.add(actor)) {
                    total++;
                    if (teamLogins.contains(actor.login)) {
                        teamTotal++;
                        team.add(actor);
                    }
                } else {
                    notCounted.add(actor);
                }
            }
        }
    }
}
