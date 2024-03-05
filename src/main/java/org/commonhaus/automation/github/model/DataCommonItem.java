package org.commonhaus.automation.github.model;

import java.util.Date;

import jakarta.json.JsonObject;

public class DataCommonItem extends DataCommonObject {

    static final String ISSUE_FIELDS = COMMON_OBJECT_FIELDS + """
            number
            title
            closed
            closedAt
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

    public DataCommonItem(JsonObject object) {
        super(object);

        this.number = JsonAttribute.number.integerFrom(object);
        this.title = JsonAttribute.title.stringFrom(object);

        this.closedAt = JsonAttribute.closedAt.dateFrom(object);
        this.closed = JsonAttribute.closed.booleanFromOrFalse(object);
    }
}
