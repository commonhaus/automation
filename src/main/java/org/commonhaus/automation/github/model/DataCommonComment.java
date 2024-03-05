package org.commonhaus.automation.github.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import org.commonhaus.automation.github.model.QueryHelper.QueryContext;

import io.smallrye.graphql.client.Response;

public class DataCommonComment extends DataCommonObject {

    static final String COMMENT_FIELDS_MIN = COMMON_OBJECT_MIN + """
            databaseId
            """;

    protected static final String COMMENT_FIELDS = COMMON_OBJECT_FIELDS + """
            databaseId
            body
            includesCreatedEdit
            viewerDidAuthor
            """;

    public final Integer databaseId;

    public DataCommonComment(JsonObject object) {
        super(object);
        this.databaseId = JsonAttribute.databaseId.integerFrom(object);
    }

    /** package private. See QueryHelper / QueryContext */
    static DataCommonComment findBotComment(QueryContext queryContext, String itemId, Integer commentId) {
        if (commentId != null) {
            // we have a commentId, so we can just fetch it directly
            Map<String, Object> variables = new HashMap<>();
            variables.put("commentId", commentId);
            Response response = queryContext.execQuerySync("""
                    query($commentId: ID!) {
                        node(id: $commentId) {
                            ... on IssueComment {
                                """ + COMMENT_FIELDS_MIN + """
                    }
                    ... on PullRequestReviewComment {
                        """ + COMMENT_FIELDS_MIN + """
                    }
                    ... on DiscussionComment {
                        """ + COMMENT_FIELDS_MIN + """
                            }
                        }
                    }
                    """, variables);
            if (response.hasError()) {
                if (queryContext.hasNotFound()) {
                    queryContext.clearErrors();
                } else {
                    return null;
                }
            }
            JsonObject node = JsonAttribute.node.jsonObjectFrom(response.getData());
            DataCommonComment ec = new DataCommonComment(node);
            if (queryContext.isBot(ec.author.login)) {
                return ec;
            }
        }

        Collection<DataCommonComment> allComments = queryComments(queryContext, itemId, true);
        return allComments == null || allComments.isEmpty() ? null : allComments.iterator().next();

        //Map<String, Object> variables = new HashMap<>();
        //variables.put("itemId", itemId);
        //
        //JsonObject pageInfo;
        //String cursor = null;
        //
        //// Paginate.. excessive?, but..
        //do {
        //    variables.put("after", cursor);
        //    Response response = queryContext.execQuerySync("""
        //            query($itemId: ID!, $after: String) {
        //                node(id: $itemId) {
        //                    ... on Issue {
        //                        comments(first: 50, after: $after, orderBy: {field: UPDATED_AT, direction: DESC}) {
        //                            nodes {
        //                                """ + COMMENT_FIELDS_MIN + """
        //                    }
        //                }
        //            }
        //            ... on PullRequest {
        //                comments(first: 50, after: $after, orderBy: {field: UPDATED_AT, direction: DESC}) {
        //                    nodes {
        //                        """ + COMMENT_FIELDS_MIN + """
        //                    }
        //                }
        //            }
        //            ... on Discussion {
        //                comments(first: 50, after: $after) {
        //                    nodes {
        //                        """ + COMMENT_FIELDS_MIN + """
        //                            }
        //                        }
        //                    }
        //                }
        //            }
        //            """, variables);
        //    if (response.hasError()) {
        //        if (queryContext.hasNotFound()) {
        //            queryContext.clearErrors();
        //        }
        //        return null;
        //    }
        //    JsonObject node = JsonAttribute.node.jsonObjectFrom(response.getData());
        //    JsonObject comments = JsonAttribute.comments.jsonObjectFrom(node);
        //    JsonArray nodes = JsonAttribute.nodes.jsonArrayFrom(comments);
        //    for (JsonObject comment : nodes.getValuesAs(JsonObject.class)) {
        //        DataCommonComment cc = new DataCommonComment(comment);
        //        if (queryContext.isBot(cc.author.login)) {
        //            // we found it! bail ASAP
        //            return cc;
        //        }
        //    }
        //    pageInfo = JsonAttribute.node.jsonObjectFrom(comments);
        //    cursor = JsonAttribute.endCursor.stringFrom(pageInfo);
        //} while (JsonAttribute.hasNextPage.booleanFromOrFalse(pageInfo));
        // we didn't find one...
        //return null;
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
                                        """ + COMMENT_FIELDS_MIN + """
                            }
                        }
                    }
                    ... on PullRequest {
                        comments(first: 50, after: $after, orderBy: {field: UPDATED_AT, direction: DESC}) {
                            nodes {
                                """ + COMMENT_FIELDS_MIN + """
                            }
                        }
                    }
                    ... on Discussion {
                        comments(first: 50, after: $after) {
                            nodes {
                                """ + COMMENT_FIELDS_MIN + """
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
