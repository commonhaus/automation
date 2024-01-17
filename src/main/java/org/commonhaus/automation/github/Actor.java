package org.commonhaus.automation.github;

import jakarta.json.JsonObject;

public class Actor extends GHType {
    public final String login;
    public final String url;
    public final String avatarUrl;

    public Actor(JsonObject author) {
        super(author);
        this.url = JsonAttribute.url.stringFrom(author);
        this.avatarUrl = JsonAttribute.avatarUrl.stringFrom(author);
        this.login = JsonAttribute.login.stringFrom(author);
    }
}