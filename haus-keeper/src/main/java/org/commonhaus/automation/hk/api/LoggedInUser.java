package org.commonhaus.automation.hk.api;

import java.util.HashSet;
import java.util.Set;

import jakarta.json.JsonObject;

import org.commonhaus.automation.github.context.JsonAttribute;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * DTO for User data returned to the web interface
 */
@RegisterForReflection
class LoggedInUser {
    final long id;
    final String login;
    final String nodeId;

    String name;
    String avatarUrl;
    String url;
    Set<String> roles = new HashSet<>();
    boolean hasApplication = false;

    public LoggedInUser(long id, String login, String nodeId) {
        this.id = id;
        this.login = login;
        this.nodeId = nodeId;
    }

    public LoggedInUser(JsonObject jsonObject) {
        this.id = JsonAttribute.id.longFrom(jsonObject);
        this.login = JsonAttribute.login.stringFrom(jsonObject);
        this.nodeId = JsonAttribute.node_id.stringFrom(jsonObject);

        // optional: may not be present
        this.name = JsonAttribute.name.stringFrom(jsonObject);
        this.avatarUrl = JsonAttribute.avatarUrl.stringFrom(jsonObject);
        this.url = JsonAttribute.url.stringFrom(jsonObject);
    }

    @Override
    public String toString() {
        return "GitHubUser [login=%s, id=%s, nodeId=%s, roles=%s]"
                .formatted(login, id, nodeId, roles);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (int) (id ^ (id >>> 32));
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        LoggedInUser other = (LoggedInUser) obj;
        if (id != other.id)
            return false;
        return true;
    }
}
