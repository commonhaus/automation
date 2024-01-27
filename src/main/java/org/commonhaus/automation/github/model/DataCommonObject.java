package org.commonhaus.automation.github.model;

import java.util.Date;

import jakarta.json.JsonObject;

/**
 * Common elements for Discussions, Issues, and Pull Requests
 */
public class DataCommonObject extends DataCommonType {

    static final String COMMON_OBJECT_FIELDS = """
            id
            author {
                login
                url
                avatarUrl
            }
            editor {
                login
                url
                avatarUrl
            }
            authorAssociation
            body
            createdAt
            publishedAt
            lastEditedAt
            updatedAt
            url
                """;

    public final DataActor author;
    public final String authorAssociation;

    public final String url;

    public final Date createdAt;
    public final Date publishedAt;

    public final String body;

    // Updatable
    public final DataActor editor;
    public final Date updatedAt;
    public final Date lastEditedAt;

    public DataCommonObject(JsonObject object) {
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

        this.authorAssociation = JsonAttribute.authorAssociation.stringFrom(object);
        this.createdAt = JsonAttribute.createdAt.dateFrom(object);
        this.updatedAt = JsonAttribute.updatedAt.dateFrom(object);
        this.url = JsonAttribute.url.stringFrom(object);

        this.body = JsonAttribute.body.stringFrom(object);
    }
}
