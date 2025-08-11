package org.commonhaus.automation.github.context;

import java.time.Instant;

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
                """ + DataActor.ACTOR_FIELDS_MIN + """
            }
            url
            """.stripIndent();

    static final String COMMON_OBJECT_FIELDS = """
            id
            author {
                """ + DataActor.ACTOR_FIELDS + """
            }
            body
            createdAt
            updatedAt
            url
            """.stripIndent();

    public final DataActor author;

    public final String url;
    public final Instant createdAt;
    public final Instant updatedAt;
    public final Instant lastEditedAt;

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
        this.createdAt = JsonAttribute.createdAt.instantFrom(object);
        this.updatedAt = JsonAttribute.updatedAt.instantFrom(object);
        this.lastEditedAt = JsonAttribute.lastEditedAt.instantFrom(object);

        this.url = JsonAttribute.url.stringFrom(object);
        this.body = JsonAttribute.body.stringFrom(object);
    }

    public DataCommonObject(DataCommonObject other) {
        super(other);
        this.author = other.author;
        this.createdAt = other.createdAt;
        this.updatedAt = other.updatedAt;
        this.lastEditedAt = other.lastEditedAt;
        this.url = other.url;
        this.body = other.body;
    }

    public Instant mostRecentEdit() {
        return lastEditedAt == null ? createdAt : lastEditedAt;
    }
}
