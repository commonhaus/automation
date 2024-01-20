package org.commonhaus.automation.github.webhook;

import java.io.IOException;

import jakarta.json.JsonObject;

import org.commonhaus.automation.github.model.Discussion;
import org.commonhaus.automation.github.model.DiscussionCategory;
import org.commonhaus.automation.github.model.DiscussionComment;
import org.commonhaus.automation.github.model.JsonAttribute;
import org.commonhaus.automation.github.model.Label;
import org.kohsuke.github.GitHub;

public class WebHookDiscussion extends WebHookBase {

    public enum Type {
        answered,
        category_changed,
        closed,
        created,
        deleted,
        edited,
        labeled,
        locked,
        pinned,
        reopened,
        transferred,
        unanswered,
        unlabeled,
        unlocked,
        unpinned
    }

    public static WebHookDiscussion from(GitHub github, String action, JsonObject object) throws IOException {
        return new WebHookDiscussion(github, object, Type.valueOf(action));
    }

    public final Discussion discussion;
    public final Type type;

    // presence is type dependent
    public final DiscussionComment answer;
    public final Label label;
    public final DiscussionCategory fromCategory;
    public final String fromBody;
    public final String fromTitle;

    public WebHookDiscussion(GitHub github, JsonObject object, Type type) throws IOException {
        super(github, object);

        this.type = type;
        this.discussion = JsonAttribute.discussion.discussionFrom(object);

        // answered
        this.answer = JsonAttribute.answer.discussionCommentFrom(object);
        // labeled / unlabeled
        this.label = JsonAttribute.label.labelFrom(object);

        // category_changed, edited, transferred
        JsonObject changes = JsonAttribute.changes.jsonObjectFrom(object);
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
