package org.commonhaus.automation.github.model;

import jakarta.json.JsonObject;

import org.commonhaus.automation.github.model.EventPayload.DiscussionCommentPayload;
import org.commonhaus.automation.github.model.EventPayload.DiscussionPayload;

import io.quarkus.logging.Log;

public enum EventType {
    discussion,
    discussion_comment,
    label,
    pull_request,
    ;

    public static EventType fromString(String action) {
        return EventType.valueOf(action.toLowerCase());
    }

    public EventPayload getDataFrom(ActionType action, JsonObject jsonData) {
        return switch (this) {
            case discussion -> new DiscussionPayload(action, jsonData);
            case discussion_comment -> new DiscussionCommentPayload(action, jsonData);
            default -> {
                Log.errorf("EventType.getDataFrom: unsupported event type %s", this);
                yield null;
            }
        };
    }
}
