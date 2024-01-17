package org.commonhaus.automation.github;

import jakarta.json.JsonObject;

public class GHFrom {
    public final String from;

    public GHFrom(JsonObject object) {
        this.from = JsonAttribute.from.stringFrom(object);
    }
}
