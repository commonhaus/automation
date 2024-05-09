package org.commonhaus.automation.github.context;

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
    review_requested,
    submitted,
    synchronize,
    transferred,
    unanswered,
    unlabeled,
    unlocked,
    unpinned,
    bot_scheduled;

    public static ActionType fromString(String action) {
        return ActionType.valueOf(action.toLowerCase());
    }
}
