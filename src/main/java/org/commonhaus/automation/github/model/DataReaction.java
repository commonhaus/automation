package org.commonhaus.automation.github.model;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import org.commonhaus.automation.github.model.QueryHelper.QueryContext;
import org.kohsuke.github.ReactionContent;

import io.quarkus.logging.Log;
import io.smallrye.graphql.client.Response;

/**
 * Represents a reaction to a GraphQL Reactable object.
 *
 * This is not available to webhook events.
 */
public class DataReaction {
    static final String REACTION_FIELDS = """
            user {
                id
                login
                url
                avatarUrl
            }
            content
            createdAt
            """;

    public final DataActor user;
    public final Date createdAt;
    public final String content;
    public final ReactionContent reactionContent;
    public final String id;
    public final String reactableId; // "parent" id

    DataReaction(JsonObject object) {
        this.createdAt = JsonAttribute.createdAt.dateFrom(object);
        this.id = JsonAttribute.id.stringFrom(object);
        this.reactableId = JsonAttribute.reactableId.stringFrom(object);
        this.user = JsonAttribute.user.actorFrom(object);
        this.content = JsonAttribute.content.stringFrom(object);
        this.reactionContent = reactionContentFrom(this.content);
    }

    public String toString() {
        return String.format("Reaction [%s] on %s by %s", this.content, this.reactableId, this.user);
    }

    public static ReactionContent reactionContentFrom(String content) {
        if ("thumbs_down".equalsIgnoreCase(content)) {
            return ReactionContent.MINUS_ONE;
        }
        if ("thumbs_up".equalsIgnoreCase(content)) {
            return ReactionContent.PLUS_ONE;
        }
        for (ReactionContent rc : ReactionContent.values()) {
            if (rc.getContent().equalsIgnoreCase(content) || rc.name().equalsIgnoreCase(content)) {
                return rc;
            }
        }
        return null;
    }

    /** package private. See QueryHelper / QueryContext */
    static List<DataReaction> queryReactions(QueryContext queryContext, String reactorId) {
        if (queryContext.hasErrors()) {
            return List.of();
        }
        Log.debugf("[%s] queryReactions for reactable %s", queryContext.getLogId(), reactorId);
        List<DataReaction> reactions = new ArrayList<>();
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", reactorId);

        JsonObject pageInfo = null;
        String cursor = null;

        // paginated...
        do {
            variables.put("after", cursor);
            Response response = queryContext.execRepoQuerySync("""
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
                    """, variables);
            if (response.hasError()) {
                if (queryContext.hasNotFound()) {
                    queryContext.clearErrors();
                }
                break;
            }
            JsonObject allReactions = JsonAttribute.reactions.extractObjectFrom(response.getData(),
                    JsonAttribute.node);

            JsonArray nodes = JsonAttribute.nodes.jsonArrayFrom(allReactions);
            reactions.addAll(nodes.stream()
                    .map(JsonObject.class::cast)
                    .map(DataReaction::new)
                    .toList());

            pageInfo = JsonAttribute.pageInfo.jsonObjectFrom(allReactions);
            cursor = JsonAttribute.endCursor.stringFrom(pageInfo);
        } while (pageInfo != null && JsonAttribute.hasNextPage.booleanFromOrFalse(pageInfo));
        return reactions;
    }

    /** package private. See QueryHelper / QueryContext */
    static void addBotReaction(QueryContext queryContext, String subjectId, ReactionContent reaction) {
        Log.debugf("[%s] addBotReaction %s to reactable %s", queryContext.getLogId(), reaction.name(), subjectId);

        Map<String, Object> variables = new HashMap<>();
        variables.put("subjectId", subjectId);
        variables.put("content", reaction.name());
        queryContext.execQuerySync("""
                mutation AddReaction($subjectId: ID!, $content: ReactionContent!) {
                    addReaction(input: {
                            subjectId: $subjectId,
                            content: $content}) {
                        clientMutationId
                    }
                }""", variables);
        if (queryContext.hasNotFound()) {
            queryContext.clearErrors();
        }
    }

    /** package private. See QueryHelper / QueryContext */
    static void removeBotReaction(QueryContext queryContext, String subjectId, ReactionContent reaction) {
        if (queryContext.isDryRun()) {
            Log.infof("[%s] would remove reaction %s from %s", queryContext.getLogId(), reaction.name(), subjectId);
            return;
        }
        if (queryContext.hasErrors()) {
            Log.debugf("[%s] removeBotReaction to reactable %s; skipping modify (errors)", queryContext.getLogId(), subjectId);
            return;
        }
        Log.debugf("[%s] removeBotReaction %s to reactable %s", queryContext.getLogId(), reaction.name(), subjectId);

        Map<String, Object> variables = new HashMap<>();
        variables.put("subjectId", subjectId);
        variables.put("content", reaction.name());
        queryContext.execQuerySync("""
                mutation RemoveReaction($subjectId: ID!, $content: ReactionContent!) {
                    removeReaction(input: {
                            subjectId: $subjectId,
                            content: $content}) {
                        clientMutationId
                    }
                }""", variables);
        if (queryContext.hasNotFound()) {
            queryContext.clearErrors();
        }
    }
}
