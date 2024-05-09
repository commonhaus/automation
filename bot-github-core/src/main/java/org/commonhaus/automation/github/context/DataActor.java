package org.commonhaus.automation.github.context;

import jakarta.json.JsonObject;

import org.kohsuke.github.GHUser;

/**
 * Represents a user or bot that interacts with GitHub.
 * <p>
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

    /**
     * Use only the login for hashcode and equals.
     * Sometimes the id is present, and sometimes it isn't,
     * it depends on the invocation path and query results.
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        return prime * ((login == null) ? 0 : login.hashCode());
    }

    /**
     * Use only the login for hashcode and equals.
     * Sometimes the id is present, and sometimes it isn't,
     * it depends on the invocation path and query results.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (getClass() != obj.getClass())
            return false;
        DataActor other = (DataActor) obj;
        if (login == null) {
            return other.login == null;
        } else
            return login.equals(other.login);
    }
}
