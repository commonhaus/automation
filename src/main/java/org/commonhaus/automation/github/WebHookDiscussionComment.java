package org.commonhaus.automation.github;

import java.io.IOException;

import jakarta.json.JsonObject;

public class WebHookDiscussionComment extends WebHookBase {

    public enum Type {
        created, 
        deleted, 
        edited
    }

    public static WebHookDiscussionComment from(String action, JsonObject object) throws IOException {
        return switch (action) {
            case "created" -> new WebHookDiscussionComment(object, Type.created);
            case "deleted" -> new WebHookDiscussionComment(object, Type.deleted);
            case "edited" -> new WebHookDiscussionComment(object, Type.edited);
            default -> throw new IllegalArgumentException("Unknown type: " + action);
        };
    }

    public final Type type;
    public final Discussion discussion;
    public final DiscussionComment comment;

    // Only present for edits
    public final String fromBody;

    public WebHookDiscussionComment(JsonObject object, Type type) throws IOException {
        super(object);
        this.type = type;
        this.discussion = JsonAttribute.discussion.discussionFrom(object);
        this.comment = JsonAttribute.comment.discussionCommentFrom(object);

        JsonObject changes = JsonAttribute.changes.jsonObjectFrom(object);
        if (changes == null) {
            this.fromBody = null;
        } else {
            this.fromBody = JsonAttribute.from.stringFrom(
                JsonAttribute.body.jsonObjectFrom(changes));
        }
    }
}
