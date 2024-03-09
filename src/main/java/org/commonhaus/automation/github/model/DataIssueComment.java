package org.commonhaus.automation.github.model;

import java.util.HashMap;
import java.util.Map;

import jakarta.json.JsonObject;

import org.commonhaus.automation.github.model.QueryHelper.QueryContext;

import io.smallrye.graphql.client.Response;

public class DataIssueComment extends DataCommonComment {

    static final String ISSUE_COMMENT = COMMENT_FIELDS + """
            issue {
                id
            }
            """;

    // Issue or pull request (minimal fields)
    public final DataCommonItem issue;

    public DataIssueComment(JsonObject object) {
        super(object);
        this.issue = JsonAttribute.issue.commonItemFrom(object);
    }

    /** package private. See QueryHelper / QueryContext */
    protected static DataCommonComment addIssueComment(QueryContext queryContext, String itemId, String commentBody) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("subjectId", itemId);
        variables.put("body", commentBody);

        Response response = queryContext.execQuerySync("""
                mutation AddComment($subjectId: ID!, $body: String!) {
                    addComment(input: {
                        subjectId: $subjectId,
                        body: $body
                    }) {
                        clientMutationId
                        commentEdge {
                            node {
                                """ + ISSUE_COMMENT + """
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
        JsonObject result = JsonAttribute.updateDiscussionComment.jsonObjectFrom(response.getData());
        JsonObject commentEdge = JsonAttribute.commentEdge.jsonObjectFrom(result);
        JsonObject node = JsonAttribute.node.jsonObjectFrom(commentEdge);
        return new DataCommonComment(node);
    }

    /** package private. See QueryHelper / QueryContext */
    protected static DataIssueComment editIssueComment(QueryContext queryContext, String commentId,
            String commentBody) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", commentId);
        variables.put("body", commentBody);

        Response response = queryContext.execQuerySync("""
                mutation($id: ID!, $body: String!) {
                    updateIssueComment(input: {
                        id: $commentId,
                        body: $body
                    }) {
                        clientMutationId
                        issueComment {
                            """ + ISSUE_COMMENT + """
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
        JsonObject result = JsonAttribute.updateIssueComment.jsonObjectFrom(response.getData());
        return JsonAttribute.issueComment.issueCommentFrom(result);
    }
}
