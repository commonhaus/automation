package org.commonhaus.automation.github;

import java.io.IOException;

import jakarta.json.JsonObject;

import org.commonhaus.automation.github.model.Discussion;
import org.commonhaus.automation.github.model.DiscussionComment;
import org.commonhaus.automation.github.model.JsonAttribute;
import org.kohsuke.github.GitHub;

public class WebHookDiscussionComment extends WebHookBase {

    public enum Type {
        created,
        deleted,
        edited
    }

    public static WebHookDiscussionComment from(GitHub github, String action, JsonObject object) throws IOException {
        return new WebHookDiscussionComment(github, object, Type.valueOf(action));
    }

    public final Type type;
    public final Discussion discussion;
    public final DiscussionComment comment;

    // Only present for edits
    public final String fromBody;

    public WebHookDiscussionComment(GitHub github, JsonObject object, Type type) throws IOException {
        super(github, object);
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
