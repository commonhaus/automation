package org.commonhaus.automation.github.model;

import java.util.Date;

import jakarta.json.JsonObject;

public class DataCommonItem extends DataCommonObject {

    static final String ISSUE_FIELDS = COMMON_OBJECT_FIELDS + """
            number
            title
            closed
            closedAt
            locked
            activeLockReason
            """;

    static final String ISSUE_FIELDS_MIN = COMMON_OBJECT_MIN + """
            number
            title
            """;

    /** Issue/Discussion/PR number within repository */
    public final Integer number;
    public final String title;

    // Closable
    public final Date closedAt;
    public final boolean closed;

    // State: may only be present for webhooks (transitions)
    public final String state;
    // Reason item is in a state: may be null
    public final String stateReason;

    // Lockable
    public final boolean locked;
    public final String activeLockReason;

    public DataCommonItem(JsonObject object) {
        super(object);

        this.number = JsonAttribute.number.integerFrom(object);
        this.title = JsonAttribute.title.stringFrom(object);

        this.closedAt = JsonAttribute.closedAt.dateFrom(object);
        this.closed = JsonAttribute.closed.booleanFromOrFalse(object);

        this.state = JsonAttribute.state.stringFrom(object);
        this.stateReason = JsonAttribute.stateReason.stringFrom(object);

        this.locked = JsonAttribute.locked.booleanFromOrFalse(object);
        this.activeLockReason = JsonAttribute.activeLockReason.stringFrom(object);
    }
}
