package org.commonhaus.automation.github;

import java.util.Date;

import jakarta.json.JsonObject;

/**
 * Common elements for Discussions, Issues, and Pull Requests
 */
public class CommonObject extends CommonType {

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

    public CommonObject(JsonObject object) {
        super(object);

        if (isWebhookData()) {
            // Webhook
            this.author = JsonAttribute.user.actorFrom(object);
            this.editor = null;
            this.lastEditedAt = null;
            this.publishedAt = null;
        } else {
            // GraphQL
            this.author = JsonAttribute.author.actorFrom(object);
            this.editor = JsonAttribute.editor.actorFrom(object);
            this.lastEditedAt = JsonAttribute.lastEditedAt.dateFrom(object);
            this.publishedAt = JsonAttribute.publishedAt.dateFrom(object);
        }

        this.createdAt = JsonAttribute.createdAt.dateFrom(object);
        this.updatedAt = JsonAttribute.updatedAt.dateFrom(object);
        this.url = JsonAttribute.url.stringFrom(object);

        this.authorAssociation = JsonAttribute.authorAssociation.stringFrom(object);
        this.body = JsonAttribute.body.stringFrom(object);
    }
}
