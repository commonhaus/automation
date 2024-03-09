package org.commonhaus.automation.github.voting;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.commonhaus.automation.github.Voting;
import org.commonhaus.automation.github.model.DataActor;
import org.commonhaus.automation.github.model.DataCommonComment;
import org.commonhaus.automation.github.model.DataReaction;
import org.kohsuke.github.ReactionContent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class VoteTally {
    public static final Comparator<DataReaction> compareReactions = Comparator
            .comparing(DataReaction::date, Comparator.reverseOrder())
            .thenComparing(DataReaction::sortOrder);

    public static final Comparator<DataActor> compareActors = Comparator
            .comparing(DataActor::login);

    @JsonProperty
    final VoteInformation.Type voteType;

    @JsonProperty
    final boolean hasQuorum;

    @JsonProperty
    final String group;

    @JsonProperty
    final int groupSize;

    @JsonProperty
    final int groupVotes;

    @JsonProperty
    final int countedVotes;

    @JsonProperty
    final int droppedVotes;

    @JsonProperty
    final Voting.Threshold votingThreshold;

    @JsonProperty
    public final Map<String, Category> categories = new HashMap<>();

    @JsonProperty
    @JsonSerialize(contentUsing = VoteTally.ReactionSerializer.class)
    final List<DataReaction> duplicates = new ArrayList<>();

    @JsonProperty
    @JsonSerialize(contentUsing = VoteTally.ActorSerializer.class)
    Collection<DataActor> missingGroupActors;

    public VoteTally(VoteInformation info, Collection<DataReaction> votes, Collection<DataCommonComment> comments) {
        Set<DataActor> teamMembers = info.teamList.members;
        missingGroupActors = new HashSet<>(teamMembers);

        voteType = info.voteType;
        group = info.group;
        groupSize = teamMembers.size();
        votingThreshold = info.votingThreshold;

        Map<String, DataActor> teamLogins = teamMembers.stream().collect(Collectors.toMap(a -> a.login, a -> a));

        if (info.voteType == VoteInformation.Type.manualComments) {
            countComments(info, comments, teamLogins);
            droppedVotes = 0;
        } else {
            countReactions(info, votes, teamLogins);
            // count how many were ignored (outside category) or dropped (duplicate)
            droppedVotes = duplicates.size() + ignoredVotes();
        }

        groupVotes = categories.entrySet().stream()
                .filter(e -> !"ignored".equals(e.getKey()))
                .mapToInt(e -> e.getValue().teamTotal).sum();
        countedVotes = categories.entrySet().stream()
                .filter(e -> !"ignored".equals(e.getKey()))
                .mapToInt(e -> e.getValue().total).sum();
        hasQuorum = switch (votingThreshold) {
            case all -> groupVotes >= groupSize;
            case majority -> groupVotes > groupSize / 2;
            case supermajority -> groupVotes > groupSize * 2 / 3;
        };
    }

    public String toMarkdown() {
        // GH Markdown likes \r\n line endings
        return String.format("\r\n%s%d of %d members of @%s have voted (%s).\r\n%s",
                (hasQuorum ? "✅ " : ""),
                groupVotes, groupSize, group,
                voteType != VoteInformation.Type.manualComments ? "reaction" : "comment",
                summarizeResults());
    }

    private void countComments(VoteInformation info, Collection<DataCommonComment> comments,
            Map<String, DataActor> teamLogins) {
        Category c = categories.computeIfAbsent("comment", k -> new Category());
        Set<DataActor> seenLogins = new HashSet<>();
        for (DataCommonComment comment : comments) {
            if (seenLogins.add(comment.author)) {
                c.add(comment.author, teamLogins);
                missingGroupActors.remove(comment.author);
            }
        }
    }

    private void countReactions(VoteInformation info, Collection<DataReaction> reactions, Map<String, DataActor> teamLogins) {
        Set<DataActor> seenLogins = new HashSet<>();

        // We will count the most recent vote of each user
        // Sort by date then by order of reaction (positive to negative)
        List<DataReaction> votes = new ArrayList<>(reactions); // mutable list
        votes.sort(compareReactions);

        for (DataReaction reaction : votes) {
            DataActor user = reaction.user;

            final Category c;
            if (info.voteType != VoteInformation.Type.marthas) {
                c = categories.computeIfAbsent(DataReaction.toEmoji(reaction.reactionContent), k -> new Category());
            } else if (info.approve.contains(reaction.reactionContent)) {
                c = categories.computeIfAbsent("approve", k -> new Category());
            } else if (info.ok.contains(reaction.reactionContent)) {
                c = categories.computeIfAbsent("ok", k -> new Category());
            } else if (info.revise.contains(reaction.reactionContent)) {
                c = categories.computeIfAbsent("revise", k -> new Category());
            } else {
                // This does not count against other votes
                c = categories.computeIfAbsent("ignored", k -> new Category());
                c.add(reaction, teamLogins);
                continue;
            }

            if (seenLogins.add(user)) {
                c.add(reaction, teamLogins);
                missingGroupActors.remove(user);
            } else {
                duplicates.add(reaction);
            }
        }
    }

    String summarizeResults() {
        if (voteType != VoteInformation.Type.manualComments) {
            return "\r\n"
                    + "| Reaction | Total | Team | Voting members |\r\n"
                    + "| --- | --- | --- | --- |\r\n"
                    + categoriesToRows()
                    + "\r\n"
                    + duplicatesToString()
                    + ignoredToString();
        } else {
            Category c = categories.get("comment");
            if (c == null || c.total == 0) {
                return "\r\nNo votes (non-bot comments) found.";
            }
            return "\r\n"
                    + "The following members have commented:  \r\n"
                    + actorsToString(c.team);
        }
    }

    String categoriesToRows() {
        return categories.entrySet().stream()
                .filter(e -> !"ignored".equals(e.getKey())) // Skip ignored votes here
                .map(e -> String.format("| %s | %d | %d | %s |",
                        e.getKey(), e.getValue().total, e.getValue().teamTotal,
                        actorsToString(e.getValue().team)))
                .collect(Collectors.joining("\r\n"));
    }

    String duplicatesToString() {
        if (duplicates.isEmpty()) {
            return "";
        }
        return "\r\nThe following votes were not counted (duplicates):\r\n"
                + duplicates.stream()
                        .map(d -> String.format("[%s](%s):%s",
                                d.user.login, d.user.url, DataReaction.toEmoji(d.reactionContent)))
                        .collect(Collectors.joining(", "))
                + "\r\n";
    }

    String ignoredToString() {
        Category ignored = categories.get("ignored");
        if (ignored == null || ignored.total == 0) {
            return "";
        }
        return "\r\nThe following reactions were not counted:\r\n"
                + ignored.reactions.stream()
                        .map(DataReaction::toEmoji)
                        .collect(Collectors.joining(", "))
                + "\r\n";
    }

    String actorsToString(Collection<DataActor> actors) {
        return actors.stream()
                .map(actor -> String.format("[%s](%s)", actor.login, actor.url))
                .collect(Collectors.joining(", "));
    }

    int ignoredVotes() {
        Category ignored = categories.get("ignored");
        return ignored == null ? 0 : ignored.total;
    }

    @RegisterForReflection
    public static class Category {
        @JsonProperty
        final Set<ReactionContent> reactions = new HashSet<>();

        @JsonProperty
        @JsonSerialize(contentUsing = VoteTally.ActorSerializer.class)
        final Set<DataActor> team = new TreeSet<>(compareActors);

        @JsonProperty
        int teamTotal = 0;
        @JsonProperty
        int total = 0;

        void add(DataReaction reaction, Map<String, DataActor> teamLogins) {
            reactions.add(reaction.reactionContent);
            add(reaction.user, teamLogins);
        }

        void add(DataActor actor, Map<String, DataActor> teamLogins) {
            total++;
            if (teamLogins.get(actor.login) != null) {
                teamTotal++;
                team.add(actor);
            }
        }
    }

    public static class ReactionSerializer extends StdSerializer<DataReaction> {
        public ReactionSerializer() {
            this(null);
        }

        public ReactionSerializer(Class<DataReaction> t) {
            super(t);
        }

        @Override
        public void serialize(DataReaction reaction, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeStartObject();
            provider.defaultSerializeField("user", reaction.user, gen);
            gen.writeObjectField("createdAt", reaction.createdAt);
            gen.writeStringField("reaction", DataReaction.toEmoji(reaction));
            gen.writeEndObject();
        }
    }

    public static class ActorSerializer extends StdSerializer<DataActor> {
        public ActorSerializer() {
            this(null);
        }

        public ActorSerializer(Class<DataActor> t) {
            super(t);
        }

        @Override
        public void serialize(DataActor actor, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeStartObject();
            gen.writeStringField("login", actor.login);
            gen.writeStringField("url", actor.url);
            gen.writeStringField("avatarUrl", actor.avatarUrl);
            gen.writeEndObject();
        }
    }
}
