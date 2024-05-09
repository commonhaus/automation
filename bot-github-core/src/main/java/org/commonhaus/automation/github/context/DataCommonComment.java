package org.commonhaus.automation.github.context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import io.quarkus.logging.Log;
import io.smallrye.graphql.client.Response;

public class DataCommonComment extends DataCommonObject {

    protected static final String COMMENT_FIELDS = COMMON_OBJECT_FIELDS + """
            databaseId
            """;

    public final Integer databaseId;

    public DataCommonComment(JsonObject object) {
        super(object);
        this.databaseId = JsonAttribute.databaseId.integerFrom(object);
    }

    public DataCommonComment(DataCommonObject object) {
        super(object);
        this.databaseId = null;
    }

    /** package private. See QueryHelper / QueryContext */
    static DataCommonComment findBotComment(QueryContext queryContext, String itemId, String commentId) {
        if (commentId != null) {
            Log.debugf("[%s] findBotComment with id %s", queryContext.getLogId(), commentId);
            // we have a commentId, so we can just fetch it directly
            Map<String, Object> variables = new HashMap<>();
            variables.put("commentId", commentId);
            Response response = queryContext.execQuerySync("""
                    query($commentId: ID!) {
                        node(id: $commentId) {
                            ... on IssueComment {
                                """ + COMMENT_FIELDS + """
                    }
                    ... on DiscussionComment {
                        """ + COMMENT_FIELDS + """
                            }
                        }
                    }
                    """, variables);
            if (response.hasError()) {
                if (queryContext.hasNotFound()) {
                    queryContext.clearErrors();
                }
                return null;
            }
            JsonObject node = JsonAttribute.node.jsonObjectFrom(response.getData());
            DataCommonComment ec = new DataCommonComment(node);
            if (queryContext.isBot(ec.author.login)) {
                return ec;
            }
        }
        return null;
    }

    static List<DataCommonComment> queryComments(QueryContext queryContext, String nodeId) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("itemId", nodeId);

        JsonObject pageInfo;
        String cursor = null;

        // Paginate.. excessive?, but..
        List<DataCommonComment> allComments = new ArrayList<>();
        do {
            variables.put("after", cursor);
            Response response = queryContext.execQuerySync("""
                    query($itemId: ID!, $after: String) {
                        node(id: $itemId) {
                            ... on Issue {
                                comments(first: 50, after: $after, orderBy: {field: UPDATED_AT, direction: DESC}) {
                                    nodes {
                                        """ + COMMENT_FIELDS + """
                            }
                        }
                    }
                    ... on PullRequest {
                        comments(first: 50, after: $after, orderBy: {field: UPDATED_AT, direction: DESC}) {
                            nodes {
                                """ + COMMENT_FIELDS + """
                            }
                        }
                    }
                    ... on Discussion {
                        comments(first: 50, after: $after) {
                            nodes {
                                """ + COMMENT_FIELDS + """
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
                return null;
            }
            JsonObject node = JsonAttribute.node.jsonObjectFrom(response.getData());
            JsonObject comments = JsonAttribute.comments.jsonObjectFrom(node);
            JsonArray nodes = JsonAttribute.nodes.jsonArrayFrom(comments);
            allComments.addAll(nodes.stream()
                    .map(JsonObject.class::cast)
                    .map(DataCommonComment::new)
                    .toList());

            pageInfo = JsonAttribute.node.jsonObjectFrom(comments);
            cursor = JsonAttribute.endCursor.stringFrom(pageInfo);
        } while (JsonAttribute.hasNextPage.booleanFromOrFalse(pageInfo));
        return allComments;
    }
}
