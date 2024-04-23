package org.commonhaus.automation.github.model;

import jakarta.json.JsonObject;

import org.kohsuke.github.GHObject;

import io.quarkus.qute.TemplateData;

@TemplateData
public class DataCommonType {
    public boolean isWebhookData() {
        return this.webhook_id != null;
    }

    /** {@literal id} for GraphQL queries or {@literal node_id} for webhook events */
    public final String id;
    /** {@literal id} for webhook events */
    public final Long webhook_id;

    DataCommonType(JsonObject object) {
        String node_id = JsonAttribute.node_id.stringFrom(object);
        if (node_id != null) {
            // Webhook
            this.id = node_id;
            this.webhook_id = JsonAttribute.id.longFrom(object);
        } else {
            // GraphQL
            this.id = JsonAttribute.id.stringFrom(object);
            this.webhook_id = null;
        }
    }

    DataCommonType(GHObject ghObject) {
        this.id = ghObject.getNodeId();
        this.webhook_id = ghObject.getId();
    }

    DataCommonType(DataCommonType other) {
        this.id = other.id;
        this.webhook_id = other.webhook_id;
    }

    /** Direct construction using a node_id (for converting from webhook payload) */
    DataCommonType(String id) {
        this.id = id;
        this.webhook_id = null;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((id == null) ? 0 : id.hashCode());
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
        DataCommonType other = (DataCommonType) obj;
        if (id == null) {
            return other.id == null;
        } else
            return id.equals(other.id);
    }
}
