package org.commonhaus.automation.github.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import org.commonhaus.automation.github.model.QueryHelper.QueryContext;

import io.quarkus.logging.Log;
import io.smallrye.graphql.client.Response;

public class DataCommonComment extends DataCommonObject {

    protected static final String COMMENT_FIELDS = COMMON_OBJECT_FIELDS + """
            databaseId
            body
            """;

    public final Integer databaseId;

    public DataCommonComment(JsonObject object) {
        super(object);
        this.databaseId = JsonAttribute.databaseId.integerFrom(object);
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
                    ... on PullRequestReviewComment {
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

        Log.debugf("[%s] look for bot comment in all comments", queryContext.getLogId());
        Collection<DataCommonComment> allComments = queryComments(queryContext, itemId, true);
        return allComments == null || allComments.isEmpty() ? null : allComments.iterator().next();
    }

    public static Collection<DataCommonComment> queryComments(QueryContext queryContext, String nodeId) {
        return queryComments(queryContext, nodeId, false);
    }

    private static Collection<DataCommonComment> queryComments(QueryContext queryContext, String nodeId,
            boolean findBotComment) {
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
            if (findBotComment) {
                for (JsonObject comment : nodes.getValuesAs(JsonObject.class)) {
                    DataCommonComment cc = new DataCommonComment(comment);
                    if (queryContext.isBot(cc.author.login)) {
                        // we found it! bail ASAP
                        return List.of(cc);
                    }
                }
            } else {
                allComments.addAll(nodes.stream()
                        .map(JsonObject.class::cast)
                        .map(DataCommonComment::new)
                        .toList());
            }

            pageInfo = JsonAttribute.node.jsonObjectFrom(comments);
            cursor = JsonAttribute.endCursor.stringFrom(pageInfo);
        } while (JsonAttribute.hasNextPage.booleanFromOrFalse(pageInfo));
        return allComments;
    }
}
