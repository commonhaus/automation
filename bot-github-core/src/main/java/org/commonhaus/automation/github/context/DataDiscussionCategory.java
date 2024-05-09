package org.commonhaus.automation.github.context;

import java.util.List;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import io.quarkus.logging.Log;
import io.smallrye.graphql.client.Response;

public class DataDiscussionCategory {

    static final String DISCUSSION_CATEGORY_FIELDS = """
            id
            name
            """;

    /**
     * {@literal id} for GraphQL queries or {@literal node_id} for webhook events
     */
    public final String id;
    /** {@literal id} for webhook events */
    public final Integer webhook_id;

    public final String name;

    DataDiscussionCategory(JsonObject category) {
        String node_id = JsonAttribute.node_id.stringFrom(category);
        if (node_id != null) {
            // Webhook
            this.id = node_id;
            this.webhook_id = JsonAttribute.id.integerFrom(category);
        } else {
            // GraphQL
            this.id = JsonAttribute.id.stringFrom(category);
            this.webhook_id = null;
        }

        this.name = JsonAttribute.name.stringFrom(category);
    }

    public String toString() {
        return String.format("Discussion [%s] %s", this.id, this.name);
    }

    /** package private. See QueryHelper / QueryContext */
    static List<DataDiscussionCategory> queryDiscussionCategories(QueryContext queryContext) {
        if (queryContext.hasErrors()) {
            return List.of();
        }
        Response response = queryContext.execRepoQuerySync("""
                query($name: String!, $owner: String!) {
                    repository(owner: $owner, name: $name) {
                        discussionCategories(first: 25) {
                            nodes {
                                """ + DISCUSSION_CATEGORY_FIELDS + """
                                }
                            }
                        }
                    }
                """);
        Log.debugf("[%s] discussion categories: %s", queryContext.getLogId(), response.getData());
        if (response.hasError()) {
            return List.of();
        }

        JsonArray categories = JsonAttribute.nodes.extractArrayFrom(response.getData(),
                JsonAttribute.repository, JsonAttribute.discussionCategories);

        return categories.stream()
                .map(JsonObject.class::cast)
                .map(DataDiscussionCategory::new)
                .toList();
    }
}
