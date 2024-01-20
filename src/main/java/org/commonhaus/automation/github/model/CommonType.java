package org.commonhaus.automation.github.model;

import jakarta.json.JsonObject;

public class CommonType {
    public boolean isWebhookData() {
        return this.webhook_id != null;
    }

    /** {@literal id} for GraphQL queries or {@literal node_id} for webhook events */
    public final String id;
    /** {@literal id} for webhook events */
    public final Integer webhook_id;

    CommonType(JsonObject object) {
        String node_id = JsonAttribute.node_id.stringFrom(object);
        if (node_id != null) {
            // Webhook
            this.id = node_id;
            this.webhook_id = JsonAttribute.id.integerFrom(object);
        } else {
            // GraphQL
            this.id = JsonAttribute.id.stringFrom(object);
            this.webhook_id = null;
        }
    }

    /** Direct construction using a node_id (for converting from webhook payload) */
    CommonType(String id) {
        this.id = id;
        this.webhook_id = null;
    }
}
