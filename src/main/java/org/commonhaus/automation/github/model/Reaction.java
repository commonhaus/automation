package org.commonhaus.automation.github.model;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import org.commonhaus.automation.github.CFGHQueryHelper.RepoQuery;

import io.quarkus.logging.Log;
import io.smallrye.graphql.client.Response;

/**
 * Represents a reaction to a GraphQL Reactable object.
 *
 * This is not available to webhook events.
 */
public class Reaction {
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

    public final Actor user;
    public final Date createdAt;
    public final String content;
    public final String id;
    public final String reactableId; // "parent" id

    Reaction(JsonObject object) {
        this.content = JsonAttribute.content.stringFrom(object);
        this.createdAt = JsonAttribute.createdAt.dateFrom(object);
        this.id = JsonAttribute.id.stringFrom(object);
        this.reactableId = JsonAttribute.reactableId.stringFrom(object);
        this.user = JsonAttribute.user.actorFrom(object);
    }

    public String toString() {
        return String.format("Reaction [%s] on %s by %s", this.content, this.reactableId, this.user);
    }

    public static List<Reaction> queryReactions(RepoQuery queryContext, String reactorId) {
        if (queryContext.hasErrors()) {
            return List.of();
        }
        Log.debugf("queryReactions for reactable %s", reactorId);
        List<Reaction> reactions = new ArrayList<>();
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", reactorId);

        JsonObject pageInfo = null;
        String cursor = null;

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
            Log.debugf("reactions (%s): %s", cursor, response.getData());
            if (response.hasError()) {
                break;
            }
            JsonObject allReactions = JsonAttribute.reactions.extractObjectFrom(response.getData(),
                    JsonAttribute.node);

            JsonArray nodes = JsonAttribute.nodes.jsonArrayFrom(allReactions);
            reactions.addAll(nodes.stream()
                    .map(JsonObject.class::cast)
                    .map(Reaction::new)
                    .toList());

            pageInfo = JsonAttribute.pageInfo.jsonObjectFrom(allReactions);
            cursor = JsonAttribute.endCursor.stringFrom(pageInfo);
        } while (pageInfo != null && JsonAttribute.hasNextPage.booleanFromOrFalse(pageInfo));
        return reactions;
    }
}
