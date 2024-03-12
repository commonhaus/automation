package org.commonhaus.automation.github.model;

import java.util.Date;

import jakarta.json.JsonObject;

import io.quarkus.qute.TemplateData;

/**
 * Common elements for Discussions, Issues, and Pull Requests
 */
@TemplateData
public class DataCommonObject extends DataCommonType {

    static final String COMMON_OBJECT_MIN = """
            id
            author {
                login
                url
                avatarUrl
            }
            url
            """;

    static final String COMMON_OBJECT_FIELDS = """
            id
            author {
                login
                url
                avatarUrl
            }
            body
            createdAt
            updatedAt
            url
                """;

    public final DataActor author;

    public final String url;
    public final Date createdAt;
    public final Date updatedAt;
    // allow modification for dry run
    public String body;

    public DataCommonObject(JsonObject object) {
        super(object);

        if (isWebhookData()) {
            // Webhook
            this.author = JsonAttribute.user.actorFrom(object);
        } else {
            // GraphQL
            this.author = JsonAttribute.author.actorFrom(object);
        }
        this.createdAt = JsonAttribute.createdAt.dateFrom(object);
        this.updatedAt = JsonAttribute.updatedAt.dateFrom(object);

        this.url = JsonAttribute.url.stringFrom(object);
        this.body = JsonAttribute.body.stringFrom(object);
    }
}
