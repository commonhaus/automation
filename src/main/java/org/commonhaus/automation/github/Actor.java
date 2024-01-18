package org.commonhaus.automation.github;

import jakarta.json.JsonObject;

/**
 * Represents a user or bot that interacts with GitHub.
 *
 * The WebHook provides an id (read by GHType), GraphQL doesn't.
 */
public class Actor extends CommonType {
    public final String login;
    public final String url;
    public final String avatarUrl;

    public Actor(JsonObject author) {
        super(author);
        this.url = JsonAttribute.url.stringFrom(author);
        this.avatarUrl = JsonAttribute.avatarUrl.stringFrom(author);
        this.login = JsonAttribute.login.stringFrom(author);
    }

    public String toString() {
        return String.format("Actor [%s]", this.login);
    }
}