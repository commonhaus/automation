package org.commonhaus.automation.github.model;

import java.util.HashMap;
import java.util.Map;

import jakarta.json.JsonObject;

import org.commonhaus.automation.github.model.QueryHelper.QueryContext;

import io.smallrye.graphql.client.Response;

public class DataDiscussionComment extends DataCommonComment {

    static final String DISCUSSION_COMMENT_FIELDS = COMMENT_FIELDS + """
            discussion {
                id
            }
            """;

    /** {@literal discussion_id} for webhook events (may be null) */
    public final Integer discussion_id;
    public final DataDiscussion discussion;

    public DataDiscussionComment(JsonObject object) {
        super(object);
        this.discussion_id = JsonAttribute.discussion_id.integerFrom(object);

        // discussion may be null (webhook)
        this.discussion = JsonAttribute.discussion.discussionFrom(object);
    }

    public String toString() {
        return String.format("Comment [%s] on discussion [%s] %s", this.id, this.discussion_id, this.discussion);
    }

    /** package private. See QueryHelper / QueryContext */
    static DataDiscussionComment addComment(QueryContext queryContext, String discussionId, String markdownText) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("discussionId", discussionId);
        variables.put("comment", markdownText);

        Response response = queryContext.execQuerySync("""
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
            if (queryContext.hasNotFound()) {
                queryContext.clearErrors();
            }
            return null;
        }
        JsonObject result = JsonAttribute.addDiscussionComment.jsonObjectFrom(response.getData());
        return JsonAttribute.comment.discussionCommentFrom(result);
    }

    /** package private. See QueryHelper / QueryContext */
    static DataDiscussionComment editComment(QueryContext queryContext, String commentId, String commentBody) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("commentId", commentId);
        variables.put("body", commentBody);

        Response response = queryContext.execQuerySync("""
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
            if (queryContext.hasNotFound()) {
                queryContext.clearErrors();
            }
            return null;
        }
        JsonObject result = JsonAttribute.updateDiscussionComment.jsonObjectFrom(response.getData());
        return JsonAttribute.comment.discussionCommentFrom(result);
    }
}
