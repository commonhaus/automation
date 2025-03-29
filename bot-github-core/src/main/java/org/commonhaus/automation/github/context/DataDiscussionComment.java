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
            """.stripIndent();

    // @formatter:off
    static final String ADD_DISCUSSION_COMMENT = """
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
            """.stripIndent();

    static final String EDIT_DISCUSSION_COMMENT = """
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
            """.stripIndent();
    // @formatter:on

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
    static DataDiscussionComment addComment(GitHubQueryContext qc, String discussionId,
            String markdownText) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("discussionId", discussionId);
        variables.put("comment", markdownText);

        Response response = qc.execQuerySync(ADD_DISCUSSION_COMMENT, variables);
        if (qc.hasErrors() || response == null) {
            qc.checkRemoveNotFound();
            return null;
        }
        JsonObject result = JsonAttribute.addDiscussionComment.jsonObjectFrom(response.getData());
        return JsonAttribute.comment.discussionCommentFrom(result);
    }

    /** package private. See QueryHelper / QueryContext */
    static DataDiscussionComment editComment(GitHubQueryContext qc, String commentId,
            String commentBody) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("commentId", commentId);
        variables.put("body", commentBody);

        Response response = qc.execQuerySync(EDIT_DISCUSSION_COMMENT, variables);
        if (qc.hasErrors() || response == null) {
            qc.checkRemoveNotFound();
            return null;
        }
        JsonObject result = JsonAttribute.updateDiscussionComment.jsonObjectFrom(response.getData());
        return JsonAttribute.comment.discussionCommentFrom(result);
    }
}
