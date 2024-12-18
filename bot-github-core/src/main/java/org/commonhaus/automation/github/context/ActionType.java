package org.commonhaus.automation.github.context;

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
    bot;

    public static ActionType fromString(String action) {
        return ActionType.valueOf(action.toLowerCase());
    }
}
