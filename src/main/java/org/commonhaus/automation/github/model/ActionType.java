package org.commonhaus.automation.github.model;

public enum ActionType {
    answered,
    category_changed,
    closed,
    created,
    deleted,
    edited,
    labeled,
    locked,
    opened,
    pinned,
    reopened,
    transferred,
    unanswered,
    unlabeled,
    unlocked,
    unpinned;

    public static ActionType fromString(String action) {
        return ActionType.valueOf(action.toLowerCase());
    }
}
