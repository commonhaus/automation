package org.commonhaus.automation.github.model;

import jakarta.json.JsonObject;

import org.kohsuke.github.GHUser;

/**
 * Represents a user or bot that interacts with GitHub.
 *
 * The WebHook provides an id (read by GHType), GraphQL doesn't.
 */
public class DataActor extends DataCommonType {
    public final String login;
    public final String url;
    public final String avatarUrl;

    public DataActor(JsonObject senderUser) {
        super(senderUser);
        this.url = JsonAttribute.url.stringFrom(senderUser);
        this.avatarUrl = JsonAttribute.avatarUrl.stringFrom(senderUser);
        this.login = JsonAttribute.login.stringFrom(senderUser);
    }

    public DataActor(GHUser senderUser) {
        super(senderUser);
        this.url = senderUser.getUrl().toString();
        this.avatarUrl = senderUser.getAvatarUrl();
        this.login = senderUser.getLogin();
    }

    public String login() {
        return login;
    }

    public String toString() {
        return String.format("Actor[%s]", this.login);
    }
}
