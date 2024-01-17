package org.commonhaus.automation.github;

import jakarta.json.JsonObject;

public class Label extends GHType {

    public final String color;
    public final boolean isDefault;
    public final String description;
    public final String name;
    public final String url;

    public Label(JsonObject object) {
        super(object);
        this.color = JsonAttribute.color.stringFrom(object);
        this.isDefault = JsonAttribute.isDefault.booleanFromOrFalse(object);
        this.description = JsonAttribute.description.stringFrom(object);
        this.name = JsonAttribute.name.stringFrom(object);
        this.url = JsonAttribute.url.stringFrom(object);
    }
}
