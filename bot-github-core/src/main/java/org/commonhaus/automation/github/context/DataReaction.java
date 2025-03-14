package org.commonhaus.automation.github.context;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import org.kohsuke.github.ReactionContent;

import io.quarkus.logging.Log;
import io.smallrye.graphql.client.Response;

/**
 * Represents a reaction to a GraphQL Reactable object.
 * <p>
 * This is not available to webhook events.
 */
public class DataReaction {
    static final ReactionContent[] ORDER = {
            ReactionContent.ROCKET,
            ReactionContent.HEART,
            ReactionContent.HOORAY,
            ReactionContent.LAUGH,
            ReactionContent.PLUS_ONE,
            ReactionContent.EYES,
            ReactionContent.CONFUSED,
            ReactionContent.MINUS_ONE,
    };

    static final String REACTION_FIELDS = """
            user {
                """ + DataActor.ACTOR_FIELDS_MIN + """
            }
            content
            createdAt
            """.stripIndent();

    // @formatter:off
    static final String QUERY_REACTIONS = """
            query($id: ID!, $after: String) {
                node(id: $id) {
                    ... on Reactable {
                        reactions(first: 100, after: $after) {
                            nodes {
                                """ + REACTION_FIELDS + """
                            }
                            pageInfo {
                                hasNextPage
                                endCursor
                            }
                        }
                    }
                }
            }
            """.stripIndent();

    static final String ADD_BOT_REACTION = """
            mutation AddReaction($subjectId: ID!, $content: ReactionContent!) {
                addReaction(input: {
                        subjectId: $subjectId,
                        content: $content}) {
                    clientMutationId
                }
            }
            """.stripIndent();

    static final String REMOVE_BOT_REACTION = """
            mutation RemoveReaction($subjectId: ID!, $content: ReactionContent!) {
                removeReaction(input: {
                        subjectId: $subjectId,
                        content: $content}) {
                    clientMutationId
                }
            }
            """.stripIndent();
    // @formatter:on

    public final DataActor user;
    public final Date createdAt;
    public final String content;
    public final ReactionContent reactionContent;
    public final String reactableId; // "parent" id
    public final int sortOrder;

    DataReaction(JsonObject object) {
        this.createdAt = JsonAttribute.createdAt.dateFrom(object);
        this.reactableId = JsonAttribute.reactableId.stringFrom(object);
        this.user = JsonAttribute.user.actorFrom(object);
        this.content = JsonAttribute.content.stringFrom(object);
        this.reactionContent = reactionContentFrom(this.content);
        this.sortOrder = sortOrderFor(this.reactionContent);
    }

    public DataReaction(DataActor user, String content, Date createdAt) {
        this.user = user;
        this.content = content;
        this.reactionContent = reactionContentFrom(this.content);
        this.sortOrder = sortOrderFor(this.reactionContent);
        this.createdAt = createdAt;
        this.reactableId = null;
    }

    public static String toEmoji(DataReaction reaction) {
        if (reaction.reactionContent == null) {
            return "❓";
        }
        return toEmoji(reaction.reactionContent);
    }

    public static String toEmoji(ReactionContent content) {
        return switch (content) {
            case ROCKET -> "🚀";
            case HEART -> "❤️";
            case HOORAY -> "🎉";
            case LAUGH -> "😄";
            case PLUS_ONE -> "👍";
            case EYES -> "👀";
            case CONFUSED -> "😕";
            case MINUS_ONE -> "👎";
        };
    }

    public Date date() {
        return this.createdAt;
    }

    public int sortOrder() {
        return this.sortOrder;
    }

    public String toString() {
        return "Reaction [%s] on %s by %s".formatted(this.content, this.reactableId, this.user);
    }

    private static int sortOrderFor(ReactionContent content) {
        for (int i = 0; i < ORDER.length; i++) {
            if (ORDER[i] == content) {
                return i;
            }
        }
        return ORDER.length;
    }

    public static ReactionContent reactionContentFrom(String content) {
        if ("thumbs_down".equalsIgnoreCase(content)) {
            return ReactionContent.MINUS_ONE;
        }
        if ("thumbs_up".equalsIgnoreCase(content)) {
            return ReactionContent.PLUS_ONE;
        }
        if ("tada".equalsIgnoreCase(content)) {
            return ReactionContent.HOORAY;
        }
        for (ReactionContent rc : ReactionContent.values()) {
            if (rc.getContent().equalsIgnoreCase(content) || rc.name().equalsIgnoreCase(content)) {
                return rc;
            }
        }
        return null;
    }

    /** package private. See QueryHelper / QueryContext */
    static List<DataReaction> queryReactions(QueryContext qc, String reactorId) {
        if (qc.hasErrors()) {
            return List.of();
        }
        Log.debugf("[%s] queryReactions for reactable %s", qc.getLogId(), reactorId);
        List<DataReaction> reactions = new ArrayList<>();
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", reactorId);

        DataPageInfo pageInfo = new DataPageInfo(null, false);

        // paginated...
        do {
            variables.put("after", pageInfo.cursor());
            Response response = qc.execRepoQuerySync(QUERY_REACTIONS, variables);
            if (qc.hasErrors()) {
                qc.checkRemoveNotFound();
                break;
            }
            JsonObject allReactions = JsonAttribute.reactions.extractObjectFrom(response.getData(),
                    JsonAttribute.node);

            JsonArray nodes = JsonAttribute.nodes.jsonArrayFrom(allReactions);
            reactions.addAll(nodes.stream()
                    .map(JsonObject.class::cast)
                    .map(DataReaction::new)
                    .toList());

            pageInfo = JsonAttribute.pageInfo.pageInfoFrom(allReactions);
        } while (pageInfo.hasNextPage());
        return reactions;
    }

    /** package private. See QueryHelper / QueryContext */
    static void addBotReaction(QueryContext qc, String subjectId,
            ReactionContent reaction) {
        Log.debugf("[%s] addBotReaction %s to reactable %s", qc.getLogId(), reaction.name(), subjectId);

        Map<String, Object> variables = new HashMap<>();
        variables.put("subjectId", subjectId);
        variables.put("content", reaction.name());
        qc.execQuerySync(ADD_BOT_REACTION, variables);

        qc.checkRemoveNotFound();
        if (qc.hasErrors()) {
            Log.errorf("[%s] removeBotReaction encountered errors: %s", qc.getLogId(), qc.bundleExceptions());
            qc.clearErrors();
        }
    }

    /** package private. See QueryHelper / QueryContext */
    static void removeBotReaction(QueryContext qc, String subjectId,
            ReactionContent reaction) {
        if (qc.isDryRun()) {
            Log.infof("[%s] would remove reaction %s from %s", qc.getLogId(), reaction.name(), subjectId);
            return;
        }
        if (qc.hasErrors()) {
            Log.debugf("[%s] removeBotReaction to reactable %s; skipping modify (errors)", qc.getLogId(), subjectId);
            return;
        }
        Log.debugf("[%s] removeBotReaction %s to reactable %s", qc.getLogId(), reaction.name(), subjectId);

        Map<String, Object> variables = new HashMap<>();
        variables.put("subjectId", subjectId);
        variables.put("content", reaction.name());
        qc.execQuerySync(REMOVE_BOT_REACTION, variables);

        qc.checkRemoveNotFound();
        if (qc.hasErrors()) {
            Log.errorf("[%s] removeBotReaction encountered errors: %s", qc.getLogId(), qc.bundleExceptions());
            qc.clearErrors();
        }
    }
}
