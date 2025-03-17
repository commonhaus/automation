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
            """.stripIndent();

    // @formatter:off
    protected final static String QUERY_ALL_COMMENTS = """
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
            """.stripIndent();

    public static final String QUERY_COMMENT = """
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
            """.stripIndent();
    // @formatter:on

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
    static DataCommonComment findComment(QueryContext qc, String itemId, String commentId) {
        if (commentId != null) {
            Log.debugf("[%s] findComment with id %s", qc.getLogId(), commentId);
            // we have a commentId, so we can just fetch it directly
            Map<String, Object> variables = new HashMap<>();
            variables.put("commentId", commentId);
            Response response = qc.execQuerySync(QUERY_COMMENT, variables);
            if (qc.hasErrors() || response == null) {
                qc.checkRemoveNotFound();
                return null;
            }
            JsonObject node = JsonAttribute.node.jsonObjectFrom(response.getData());
            DataCommonComment ec = new DataCommonComment(node);
            if (qc.isBot(ec.author.login)) {
                return ec;
            }
        }
        return null;
    }

    static List<DataCommonComment> queryComments(QueryContext qc, String nodeId) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("itemId", nodeId);

        DataPageInfo pageInfo = new DataPageInfo(null, false);

        // Paginate.. excessive?, but..
        List<DataCommonComment> allComments = new ArrayList<>();
        do {
            variables.put("after", pageInfo.cursor());
            Response response = qc.execQuerySync(QUERY_ALL_COMMENTS, variables);
            if (qc.hasErrors() || response == null) {
                qc.checkRemoveNotFound();
                return null;
            }
            JsonObject node = JsonAttribute.node.jsonObjectFrom(response.getData());
            JsonObject comments = JsonAttribute.comments.jsonObjectFrom(node);
            JsonArray nodes = JsonAttribute.nodes.jsonArrayFrom(comments);
            allComments.addAll(nodes.stream()
                    .map(JsonObject.class::cast)
                    .map(DataCommonComment::new)
                    .toList());

            pageInfo = JsonAttribute.pageInfo.pageInfoFrom(comments);
        } while (pageInfo.hasNextPage());
        return allComments;
    }
}
