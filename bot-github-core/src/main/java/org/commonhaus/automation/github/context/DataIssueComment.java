package org.commonhaus.automation.github.context;

import java.util.HashMap;
import java.util.Map;

import jakarta.json.JsonObject;

import io.smallrye.graphql.client.Response;

public class DataIssueComment extends DataCommonComment {

    static final String ISSUE_COMMENT = COMMENT_FIELDS + """
            issue {
                id
            }
            """;

    // Issue or pull request (minimal fields)
    public final DataCommonItem issue;

    DataIssueComment(JsonObject object) {
        super(object);
        this.issue = JsonAttribute.issue.commonItemFrom(object);
    }

    /** package private. See QueryHelper / QueryContext */
    static DataCommonComment addIssueComment(QueryContext qc, String itemId,
            String commentBody) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("subjectId", itemId);
        variables.put("body", commentBody);

        Response response = qc.execQuerySync("""
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
            qc.clearNotFound();
            return null;
        }
        JsonObject result = JsonAttribute.updateDiscussionComment.jsonObjectFrom(response.getData());
        JsonObject commentEdge = JsonAttribute.commentEdge.jsonObjectFrom(result);
        return JsonAttribute.node.issueCommentFrom(commentEdge);
    }

    /** package private. See QueryHelper / QueryContext */
    static DataIssueComment editIssueComment(QueryContext qc, String commentId,
            String commentBody) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("commentId", commentId);
        variables.put("body", commentBody);

        Response response = qc.execQuerySync("""
                mutation($commentId: ID!, $body: String!) {
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
            qc.clearNotFound();
            return null;
        }
        JsonObject result = JsonAttribute.updateIssueComment.jsonObjectFrom(response.getData());
        return JsonAttribute.issueComment.issueCommentFrom(result);
    }
}
