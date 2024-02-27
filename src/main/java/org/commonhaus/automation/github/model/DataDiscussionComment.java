package org.commonhaus.automation.github.model;

import java.util.HashMap;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.commonhaus.automation.github.EventData;
import org.commonhaus.automation.github.model.QueryHelper.QueryContext;

import io.smallrye.graphql.client.Response;

public class DataDiscussionComment extends DataCommonComment {

    static final String DISCUSSION_COMMENT_MIN = COMMENT_FIELDS_MIN + """
            discussion {
                id
            }
            """;
    static final String DISCUSSION_COMMENT_FIELDS = COMMENT_FIELDS + """
            isAnswer
            discussion {
                id
            }
            """;

    /** {@literal discussion_id} for webhook events (may be null) */
    public final Integer discussion_id;
    /** {@literal parent_id} for webhook events (may be null) */
    public final Integer parent_id;
    public final boolean isAnswer;

    public final DataDiscussion discussion;

    public DataDiscussionComment(JsonObject object) {
        super(object);
        this.discussion_id = JsonAttribute.discussion_id.integerFrom(object);
        this.parent_id = JsonAttribute.parent_id.integerFrom(object);

        // discussion may be null (webhook)
        this.discussion = JsonAttribute.discussion.discussionFrom(object);
        this.isAnswer = JsonAttribute.isAnswer.booleanFromOrFalse(object);
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
    static DataDiscussionComment editComment(QueryContext queryContext, DataCommonComment comment, String commentBody) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("commentId", comment.id);
        variables.put("body", commentBody);

        Response response = queryContext.execQuerySync("""
                mutation($commentId: ID!, $body: String!) {
                    updateDiscussionComment(input: {
                        commentId: $commentId,
                        body: $body
                    }) {
                        clientMutationId
                        comment {
                            """ + DISCUSSION_COMMENT_MIN + """
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

    /** package private. See QueryHelper / QueryContext */
    static DataDiscussionComment createFakeComment(EventData eventData, String markdownText) {
        JsonObject discussion = JsonAttribute.discussion.jsonObjectFrom(eventData.getJsonData());
        JsonObject object = Json.createObjectBuilder()
                .add("id", "placeholder")
                .add("databaseId", 0)
                .add("author", JsonAttribute.user.jsonObjectFrom(discussion))
                .add("discussion", discussion)
                .add("body", markdownText)
                .add("createdAt", JsonAttribute.createdAt.stringFrom(discussion))
                .add("publishedAt", JsonAttribute.createdAt.stringFrom(discussion))
                .add("updatedAt", JsonAttribute.createdAt.stringFrom(discussion))
                .build();
        return new DataDiscussionComment(object);
    }
}
