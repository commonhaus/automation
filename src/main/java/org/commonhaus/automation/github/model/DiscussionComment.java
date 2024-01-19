package org.commonhaus.automation.github.model;

import java.util.Date;

import jakarta.json.JsonObject;

public class DiscussionComment extends CommonComment {

    static final String DISCUSSION_COMMENT_FIELDS = COMMENT_FIELDS + """
            discussion {
                """ + Discussion.DISCUSSION_FIELDS + """
            }
            isAnswer
            """;
    static final String DISCUSSION_COMMENT_WITH_REPLY_FIELDS = DISCUSSION_COMMENT_FIELDS + """
            replyTo {
                """ + DISCUSSION_COMMENT_FIELDS + """
            }
            """;

    /** {@literal discussion_id} for webhook events (may be null) */
    public final Integer discussion_id;
    /** {@literal parent_id} for webhook events (may be null) */
    public final Integer parent_id;

    public final Date deletedAt;
    public final boolean isAnswer;

    public final Discussion discussion;
    public final DiscussionComment replyTo;

    public final boolean viewerCanDelete;
    public final boolean viewerCanMarkAsAnswer;
    public final boolean viewerCanMinimize;
    public final boolean viewerCanReact;
    public final boolean viewerCanUnmarkAsAnswer;
    public final boolean viewerCanUpdate;
    public final boolean viewerCanUpvote;
    public final String viewerCannotUpdateReasons;
    public final boolean viewerHasUpvoted;

    public DiscussionComment(JsonObject object) {
        super(object);
        this.discussion_id = JsonAttribute.discussion_id.integerFrom(object);
        this.parent_id = JsonAttribute.parent_id.integerFrom(object);

        // discussion may be null (webhook)
        this.discussion = JsonAttribute.discussion.discussionFrom(object);
        this.replyTo = JsonAttribute.replyTo.discussionCommentFrom(object);

        this.deletedAt = JsonAttribute.deletedAt.dateFrom(object);
        this.isAnswer = JsonAttribute.isAnswer.booleanFromOrFalse(object);

        this.viewerCanDelete = JsonAttribute.viewerCanDelete.booleanFromOrFalse(object);
        this.viewerCanMarkAsAnswer = JsonAttribute.viewerCanMarkAsAnswer.booleanFromOrFalse(object);
        this.viewerCanMinimize = JsonAttribute.viewerCanMinimize.booleanFromOrFalse(object);
        this.viewerCanReact = JsonAttribute.viewerCanReact.booleanFromOrFalse(object);
        this.viewerCanUnmarkAsAnswer = JsonAttribute.viewerCanUnmarkAsAnswer.booleanFromOrFalse(object);
        this.viewerCanUpdate = JsonAttribute.viewerCanUpdate.booleanFromOrFalse(object);
        this.viewerCanUpvote = JsonAttribute.viewerCanUpvote.booleanFromOrFalse(object);
        this.viewerCannotUpdateReasons = JsonAttribute.viewerCannotUpdateReasons.stringFrom(object);
        this.viewerHasUpvoted = JsonAttribute.viewerHasUpvoted.booleanFromOrFalse(object);
    }

    public String toString() {
        return String.format("Comment [%s] on discussion [%s] %s", this.id, this.discussion_id, this.discussion);
    }
}
