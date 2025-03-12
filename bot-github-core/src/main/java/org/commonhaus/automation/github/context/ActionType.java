package org.commonhaus.automation.github.context;

import io.quarkus.logging.Log;

public enum ActionType {
    added,
    answered,
    assigned,
    category_changed,
    closed,
    created,
    deleted,
    edited,
    labeled,
    locked,
    opened,
    pinned,
    removed,
    reopened,
    review_requested,
    submitted,
    synchronize,
    transferred,
    unassigned,
    unanswered,
    unlabeled,
    unlocked,
    unpinned,
    bot,
    unknown;

    public static ActionType fromString(String action) {
        if (action != null) {
            for (var x : ActionType.values()) {
                if (x.name().equalsIgnoreCase(action)) {
                    return x;
                }
            }
        }
        Log.warnf("Unknown action type: %s", action);
        return unknown;
    }
}
