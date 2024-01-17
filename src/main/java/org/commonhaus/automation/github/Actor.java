package org.commonhaus.automation.github;

import jakarta.json.JsonObject;

public class Actor {
    /** {@literal id} for GraphQL queries or {@literal node_id} for webhook events */ 
    public final String id;
    /** {@literal id} for webhook events */
    public final Integer webhook_id;

    public final String login;
    public final String url;
    public final String avatarUrl;

    public Actor(JsonObject author) {
        String node_id = JsonAttribute.node_id.stringFrom(author);
        if (node_id != null) {
            // Webhook
            this.id = node_id;
            this.webhook_id = JsonAttribute.id.integerFrom(author);
            this.url = JsonAttribute.html_url.stringFrom(author);
            this.avatarUrl = JsonAttribute.avatar_url.stringFrom(author);
        } else {
            // GraphQL
            this.id = JsonAttribute.id.stringFrom(author);
            this.webhook_id = null;
            this.url = JsonAttribute.url.stringFrom(author);
            this.avatarUrl = JsonAttribute.avatarUrl.stringFrom(author);
        }
        this.login = JsonAttribute.login.stringFrom(author);
    }
}