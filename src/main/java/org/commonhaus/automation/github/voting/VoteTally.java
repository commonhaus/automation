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
import org.commonhaus.automation.github.model.DataPullRequestReview;
import org.commonhaus.automation.github.model.DataReaction;
import org.kohsuke.github.ReactionContent;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    @JsonIgnore
    final boolean notMarthasMethod;
    @JsonIgnore
    final boolean isPullRequest;

    public VoteTally(VoteInformation info, List<DataReaction> votes, List<DataCommonComment> comments) {
        this.isPullRequest = info.isPullRequest();

        Set<DataActor> teamMembers = info.teamList.members;
        missingGroupActors = new HashSet<>(teamMembers);

        voteType = info.voteType;
        group = info.group;
        groupSize = teamMembers.size();
        votingThreshold = info.votingThreshold;
        notMarthasMethod = (info.ok.size() + info.revise.size() + info.approve.size()) == 0;

        Map<String, DataActor> teamLogins = teamMembers.stream().collect(Collectors.toMap(a -> a.login, a -> a));
        Set<DataActor> seenLogins = new HashSet<>();

        if (info.voteType == VoteInformation.Type.manualComments) {
            if (isPullRequest) {
                reviewsToComments(info.getReviews(), comments);
            }
            countComments(info, comments, seenLogins, teamLogins);
            droppedVotes = 0;
        } else {
            if (isPullRequest) {
                reviewsToVotes(info.getReviews(), votes);
            }
            countReactions(info, votes, seenLogins, teamLogins);
            // count how many were ignored (outside category) or dropped (duplicate)
            droppedVotes = duplicates.size() + ignoredVotes();
        }
        missingGroupActors.removeAll(seenLogins);

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
        return String.format("\r\n%s%d of %d members of @%s have voted (%s%s).\r\n%s",
                (hasQuorum ? "✅ " : ""),
                groupVotes, groupSize, group,
                voteType != VoteInformation.Type.manualComments ? "reaction" : "comment",
                isPullRequest ? " or review" : "",
                summarizeResults());
    }

    private void reviewsToComments(Collection<DataPullRequestReview> reviews, List<DataCommonComment> comments) {
        List<DataCommonComment> reactions = new ArrayList<>();
        // translate review states into reaction votes
        for (DataPullRequestReview review : reviews) {
            reactions.add(new DataCommonComment(review));
        }
        comments.addAll(0, reactions);
    }

    private void reviewsToVotes(Collection<DataPullRequestReview> reviews, List<DataReaction> votes) {
        List<DataReaction> reactions = new ArrayList<>();
        // translate review states into reaction votes
        for (DataPullRequestReview review : reviews) {
            if (review.state.equals("APPROVED")) {
                reactions.add(new DataReaction(review.author, ReactionContent.PLUS_ONE.getContent(), review.submittedAt));
            } else if (review.state.equals("CHANGES_REQUESTED")) {
                reactions.add(new DataReaction(review.author, ReactionContent.MINUS_ONE.getContent(), review.submittedAt));
            } else if (review.state.equals("COMMENTED")) {
                reactions.add(new DataReaction(review.author, ReactionContent.EYES.getContent(), review.submittedAt));
            }
        }
        votes.addAll(0, reactions);
    }

    private void countComments(VoteInformation info, Collection<DataCommonComment> comments,
            Set<DataActor> seenLogins, Map<String, DataActor> teamLogins) {
        Category c = categories.computeIfAbsent("comment", k -> new Category());

        for (DataCommonComment comment : comments) {
            if (seenLogins.add(comment.author)) {
                c.add(comment.author, teamLogins);
            }
        }
    }

    private void countReactions(VoteInformation info, Collection<DataReaction> reactions,
            Set<DataActor> seenLogins, Map<String, DataActor> teamLogins) {

        // We will count the most recent vote of each user
        // Sort by date then by order of reaction (positive to negative)
        List<DataReaction> votes = new ArrayList<>(reactions); // mutable list
        votes.sort(compareReactions);

        for (DataReaction reaction : votes) {
            DataActor user = reaction.user;

            final Category c;
            if (notMarthasMethod) {
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
                        .map(d -> String.format("[%s](%s)(%s)",
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
            gen.writeObjectFieldStart("user");
            gen.writeStringField("login", reaction.user.login);
            gen.writeStringField("url", reaction.user.url);
            gen.writeEndObject();
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
            gen.writeEndObject();
        }
    }
}
