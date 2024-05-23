package org.commonhaus.automation.admin.api;

import java.util.List;

import jakarta.json.JsonObject;

import org.commonhaus.automation.github.context.JsonAttribute;

/**
 * DTO for User data returned to the web interface
 */
class GitHubUser {
    final long id;
    final String login;
    final String nodeId;

    String name;
    String avatarUrl;
    String company;
    String bio;
    List<String> gh_emails;

    public GitHubUser(long id, String login, String nodeId) {
        this.id = id;
        this.login = login;
        this.nodeId = nodeId;
    }

    public GitHubUser(JsonObject jsonObject) {
        this.id = JsonAttribute.id.longFrom(jsonObject);
        this.login = JsonAttribute.login.stringFrom(jsonObject);
        this.nodeId = JsonAttribute.node_id.stringFrom(jsonObject);

        // optional: may not be present
        this.name = JsonAttribute.name.stringFrom(jsonObject);
        this.avatarUrl = JsonAttribute.avatarUrl.stringFrom(jsonObject);
        this.company = JsonAttribute.company.stringFrom(jsonObject);
        this.bio = JsonAttribute.bio.stringFrom(jsonObject);
    }

    @Override
    public String toString() {
        return "GitHubUser [login=" + login + ", id=" + id + ", nodeId=" + nodeId + ", name=" + name + ", avatarUrl="
                + avatarUrl + ", company=" + company + ", bio=" + bio + ", gh_emails=" + gh_emails + "]";
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
        GitHubUser other = (GitHubUser) obj;
        if (id != other.id)
            return false;
        return true;
    }
}
