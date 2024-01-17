package org.commonhaus.automation.github;

import java.io.IOException;

import org.kohsuke.github.GHRepository;

import jakarta.json.JsonObject;

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

    public static WebHookDiscussion from(String action, JsonObject object) throws IOException {
        return switch (action) {
            case "answered" -> new WebHookDiscussion(object, Type.answered);
            case "category_changed" -> new WebHookDiscussion(object, Type.category_changed);
            case "closed" -> new WebHookDiscussion(object, Type.closed);
            case "created" -> new WebHookDiscussion(object, Type.created);
            case "deleted" -> new WebHookDiscussion(object, Type.deleted);
            case "edited" -> new WebHookDiscussion(object, Type.edited);
            case "labeled" -> new WebHookDiscussion(object, Type.labeled);
            case "locked" -> new WebHookDiscussion(object, Type.locked);
            case "pinned" -> new WebHookDiscussion(object, Type.pinned);
            case "reopened" -> new WebHookDiscussion(object, Type.reopened);
            case "transferred" -> new WebHookDiscussion(object, Type.transferred);
            case "unanswered" -> new WebHookDiscussion(object, Type.unanswered);
            case "unlabeled" -> new WebHookDiscussion(object, Type.unlabeled);
            case "unlocked" -> new WebHookDiscussion(object, Type.unlocked);
            case "unpinned" -> new WebHookDiscussion(object, Type.unpinned);

            default -> throw new IllegalArgumentException("Unknown type: " + action);
        };
    }

    public final Discussion discussion;
    public final Type type;

    // presence is type dependent
    public final DiscussionComment answer;
    public final Label label;
    public final DiscussionCategory fromCategory;
    public final String fromBody;
    public final String fromTitle;
    public final Discussion newDiscussion;
    public final GHRepository newRepository;
    public final DiscussionComment oldAnswer;

    public WebHookDiscussion(JsonObject object, Type type) throws IOException {
        super(object);
        this.type = type;
        this.discussion = JsonAttribute.discussion.discussionFrom(object);

        // answered
        this.answer = JsonAttribute.answer.discussionCommentFrom(object);
        // unanswered
        this.oldAnswer = JsonAttribute.old_answer.discussionCommentFrom(object);
        // labeled / unlabeled
        this.label = JsonAttribute.label.labelFrom(object);

        // category_changed, edited, transferred
        JsonObject changes = JsonAttribute.changes.jsonObjectFrom(object);
        if (changes == null) {
            this.fromCategory = null;
            this.fromBody = null;
            this.fromTitle = null;
            this.newDiscussion = null;
            this.newRepository = null;
        } else {
            this.fromCategory = JsonAttribute.from.discussionCategoryFrom(
                JsonAttribute.category.jsonObjectFrom(changes));
            this.fromBody = JsonAttribute.from.stringFrom(
                    JsonAttribute.body.jsonObjectFrom(changes));
            this.fromTitle = JsonAttribute.from.stringFrom(
                    JsonAttribute.title.jsonObjectFrom(changes));
            this.newDiscussion = JsonAttribute.new_discussion.discussionFrom(changes);
            this.newRepository = repositoryFrom(JsonAttribute.new_repository.stringifyNodeFrom(changes));
        }
    }
}
