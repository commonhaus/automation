package org.commonhaus.automation.github;

import java.util.Date;

import jakarta.json.JsonObject;

public class DiscussionComment extends CommonComment {

    public final Integer discussion_id;
    public final Date deletedAt;

    Discussion discussion = null;

    public DiscussionComment(JsonObject object) {
        super(object);
        this.discussion_id = JsonAttribute.discussion_id.integerFrom(object);

        // discussion may be null (webhook)
        this.discussion = JsonAttribute.discussion.discussionFrom(object);

        this.deletedAt = JsonAttribute.deletedAt.dateFrom(object);
    }

    public String toString() {
        return String.format("Comment [%s] on discussion [%s] %s", this.id, this.discussion_id, this.discussion);
    }
}
