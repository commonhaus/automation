package org.commonhaus.automation.github.context;

import java.util.HashMap;
import java.util.Map;

import jakarta.json.JsonObject;

import io.smallrye.graphql.client.Response;

public class DataDiscussionComment extends DataCommonComment {

    static final String DISCUSSION_COMMENT_FIELDS = COMMENT_FIELDS + """
            discussion {
                id
            }
            """;

    /** {@literal discussion_id} for webhook events (possibly null) */
    public final Integer discussion_id;
    public final DataDiscussion discussion;

    DataDiscussionComment(JsonObject object) {
        super(object);
        this.discussion_id = JsonAttribute.discussion_id.integerFrom(object);

        // discussion may be null (webhook)
        this.discussion = JsonAttribute.discussion.discussionFrom(object);
    }

    public String toString() {
        return String.format("Comment [%s] on discussion [%s] %s", this.id, this.discussion_id, this.discussion);
    }

    /** package private. See QueryHelper / QueryContext */
    static DataDiscussionComment addComment(QueryContext qc, String discussionId,
            String markdownText) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("discussionId", discussionId);
        variables.put("comment", markdownText);

        Response response = qc.execQuerySync("""
                mutation AddDiscussionComment($discussionId: ID!, $comment: String!) {
                    addDiscussionComment(input: {
                        discussionId: $discussionId,
                        body: $comment
                    }) {
                        clientMutationId
                        comment {
                            """ + DISCUSSION_COMMENT_FIELDS + """
                        }
                    }
                }
                """, variables);
        if (response.hasError()) {
            qc.clearNotFound();
            return null;
        }
        JsonObject result = JsonAttribute.addDiscussionComment.jsonObjectFrom(response.getData());
        return JsonAttribute.comment.discussionCommentFrom(result);
    }

    /** package private. See QueryHelper / QueryContext */
    static DataDiscussionComment editComment(QueryContext qc, String commentId,
            String commentBody) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("commentId", commentId);
        variables.put("body", commentBody);

        Response response = qc.execQuerySync("""
                mutation($commentId: ID!, $body: String!) {
                    updateDiscussionComment(input: {
                        commentId: $commentId,
                        body: $body
                    }) {
                        clientMutationId
                        comment {
                            """ + DISCUSSION_COMMENT_FIELDS + """
                        }
                    }
                }
                """, variables);
        if (response.hasError()) {
            qc.clearNotFound();
            return null;
        }
        JsonObject result = JsonAttribute.updateDiscussionComment.jsonObjectFrom(response.getData());
        return JsonAttribute.comment.discussionCommentFrom(result);
    }
}
