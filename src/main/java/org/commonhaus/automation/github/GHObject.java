package org.commonhaus.automation.github;

import java.util.Date;
import java.util.List;

import jakarta.json.JsonObject;

/**
 * Common elements for Discussions, Issues, and Pull Requests
 */
public class GHObject {
    public boolean isWebhookData() {
        return this.webhook_id != null;
    }
    
    /** {@literal id} for GraphQL queries or {@literal node_id} for webhook events */ 
    public final String id;
    /** {@literal id} for webhook events (number) */
    public final Integer webhook_id;

    public final Actor author;
    public final String authorAssociation;

    public final String url;

    public final Date createdAt;
    public final Date publishedAt;

    public final String body;

    // Updatable
    public final Actor editor;
    public final Date updatedAt;
    public final Date lastEditedAt;

    List<Reaction> reactions = null;

    public GHObject(JsonObject object) {
        String node_id = JsonAttribute.node_id.stringFrom(object);

        if (node_id != null) {
            // Webhook
            this.id = node_id;
            this.webhook_id = JsonAttribute.id.integerFrom(object);
            this.author = JsonAttribute.user.actorFrom(object);
            this.createdAt = JsonAttribute.created_at.dateFrom(object);
            this.editor = null;
            this.lastEditedAt = null;
            this.publishedAt = null;
            this.updatedAt = JsonAttribute.updated_at.dateFrom(object);
            this.url = JsonAttribute.html_url.stringFrom(object);
        } else {
            // GraphQL
            this.id = JsonAttribute.id.stringFrom(object);
            this.webhook_id = null;
            this.author = JsonAttribute.author.actorFrom(object);
            this.createdAt = JsonAttribute.createdAt.dateFrom(object);
            this.editor = JsonAttribute.editor.actorFrom(object);
            this.lastEditedAt = JsonAttribute.lastEditedAt.dateFrom(object);
            this.publishedAt = JsonAttribute.publishedAt.dateFrom(object);
            this.updatedAt = JsonAttribute.updatedAt.dateFrom(object);
            this.url = JsonAttribute.url.stringFrom(object);
        }

        this.authorAssociation = JsonAttribute.authorAssociation.stringFrom(object);
        this.body = JsonAttribute.body.stringFrom(object);
    }
}
