package org.commonhaus.automation.github.context;

import jakarta.json.JsonObject;

import io.quarkus.qute.TemplateData;

@TemplateData
public class EventPayload {
    public final ActionType action;

    public EventPayload(ActionType action) {
        this.action = action;
    }

    @TemplateData
    public static class DiscussionPayload extends EventPayload {
        public final DataDiscussion discussion;

        // presence is action type dependent
        public final DataDiscussionComment answer;
        public final DataDiscussionCategory fromCategory;

        public final DataLabel label;
        public final String fromBody;
        public final String fromTitle;

        public DiscussionPayload(ActionType action, JsonObject jsonData) {
            super(action);
            this.discussion = JsonAttribute.discussion.discussionFrom(jsonData);

            // answered
            this.answer = JsonAttribute.answer.discussionCommentFrom(jsonData);
            // labeled / unlabeled
            this.label = JsonAttribute.label.labelFrom(jsonData);

            // category_changed, edited, transferred
            JsonObject changes = JsonAttribute.changes.jsonObjectFrom(jsonData);
            if (changes == null) {
                this.fromCategory = null;
                this.fromBody = null;
                this.fromTitle = null;
            } else {
                this.fromCategory = JsonAttribute.from.discussionCategoryFrom(
                        JsonAttribute.category.jsonObjectFrom(changes));
                this.fromBody = JsonAttribute.from.stringFrom(
                        JsonAttribute.body.jsonObjectFrom(changes));
                this.fromTitle = JsonAttribute.from.stringFrom(
                        JsonAttribute.title.jsonObjectFrom(changes));
            }
        }
    }

    @TemplateData
    public static class DiscussionCommentPayload extends DiscussionPayload {
        public final DataDiscussionComment comment;

        public DiscussionCommentPayload(ActionType action, JsonObject jsonData) {
            super(action, jsonData);
            this.comment = JsonAttribute.comment.discussionCommentFrom(jsonData);
        }
    }

    @TemplateData
    public static class CommonItemPayload extends EventPayload {
        public final DataCommonItem issue;
        public final DataCommonItem pullRequest;

        public final DataLabel label;

        public final String fromBody;
        public final String fromTitle;

        public CommonItemPayload(ActionType action, JsonObject jsonData) {
            super(action);

            this.issue = JsonAttribute.issue.commonItemFrom(jsonData);
            this.pullRequest = JsonAttribute.pullRequest.commonItemFrom(jsonData);

            // labeled / unlabeled
            this.label = JsonAttribute.label.labelFrom(jsonData);

            // edited
            JsonObject changes = JsonAttribute.changes.jsonObjectFrom(jsonData);
            if (changes == null) {
                this.fromBody = null;
                this.fromTitle = null;
            } else {
                this.fromBody = JsonAttribute.from.stringFrom(
                        JsonAttribute.body.jsonObjectFrom(changes));
                this.fromTitle = JsonAttribute.from.stringFrom(
                        JsonAttribute.title.jsonObjectFrom(changes));
            }
        }
    }

    @TemplateData
    public static class CommonItemCommentPayload extends CommonItemPayload {
        public final DataCommonComment comment;

        public CommonItemCommentPayload(ActionType action, JsonObject jsonData) {
            super(action, jsonData);
            this.comment = JsonAttribute.comment.commonCommentFrom(jsonData);
        }
    }
}
