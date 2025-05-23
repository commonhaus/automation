package org.commonhaus.automation.github.context;

import jakarta.json.JsonObject;

import org.commonhaus.automation.github.context.EventPayload.CommonItemCommentPayload;
import org.commonhaus.automation.github.context.EventPayload.CommonItemPayload;
import org.commonhaus.automation.github.context.EventPayload.DiscussionCommentPayload;
import org.commonhaus.automation.github.context.EventPayload.DiscussionPayload;

import io.quarkus.logging.Log;

public enum EventType {
    discussion,
    discussion_comment,
    issue,
    issues,
    issue_comment,
    label,
    member,
    membership,
    pull_request,
    pull_request_review,
    team,
    bot,
    unknown;

    public boolean isDiscussion() {
        return this == discussion || this == discussion_comment;
    }

    public boolean isPullRequest() {
        return this == pull_request || this == pull_request_review;
    }

    public static EventType fromString(String event) {
        if (event != null) {
            for (var x : EventType.values()) {
                if (x.name().equalsIgnoreCase(event)) {
                    return x;
                }
            }
        }
        Log.warnf("Unknown event type: %s", event);
        return unknown;
    }

    public EventPayload getDataFrom(ActionType action, JsonObject jsonData) {
        return switch (this) {
            case discussion -> new DiscussionPayload(action, jsonData);
            case discussion_comment -> new DiscussionCommentPayload(action, jsonData);
            case issues, issue, pull_request -> new CommonItemPayload(action, jsonData);
            case issue_comment -> new CommonItemCommentPayload(action, jsonData);
            default -> {
                Log.errorf("EventType.getDataFrom: unsupported event type %s", this);
                yield null;
            }
        };
    }
}
