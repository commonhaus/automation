package org.commonhaus.automation.github.context;

import jakarta.json.JsonObject;

public record DataPageInfo(String cursor, boolean hasNextPage) {

    public DataPageInfo(JsonObject pageInfo) {
        this(JsonAttribute.endCursor.stringFrom(pageInfo),
                JsonAttribute.hasNextPage.booleanFromOrFalse(pageInfo));
    }
}
