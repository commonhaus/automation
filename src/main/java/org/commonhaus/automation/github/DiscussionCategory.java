package org.commonhaus.automation.github;

import java.util.List;

import io.quarkus.logging.Log;
import io.smallrye.graphql.client.Response;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

public class DiscussionCategory {

    /** {@literal id} for GraphQL queries or {@literal node_id} for webhook events */ 
    public final String id;
    /** {@literal id} for webhook events */
    public final Integer webhook_id;

    public final String name;
    public final String description;
    public final String emoji;

    DiscussionCategory(JsonObject category) {
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
        this.description = JsonAttribute.description.stringFrom(category);
        this.emoji = JsonAttribute.emoji.stringFrom(category);
    }

    public String toString() {
        return String.format("Discussion [%s] %s %s", this.id, this.emoji, this.name);
    }

    /**
     * Exceptions and errors are captured for caller in the queryContext
     * @return list of discussion categories
     */
    static List<DiscussionCategory> queryDiscussionCategories(CFGHQueryContext queryContext) {
        if (queryContext.hasErrors()) {
            return List.of();
        }
        Response response = queryContext.execRepoQuerySync("""
                    query($name: String!, $owner: String!) {
                        repository(owner: $owner, name: $name) {
                            discussionCategories(first: 25) {
                                nodes {
                                    name
                                    description
                                    emoji
                                    id
                                }
                            }
                        }
                        }
                """);
        Log.debugf("discussion categories: %s", response.getData());
        if (response.hasError()) {
            return List.of();
        }

        JsonArray categories = JsonAttribute.nodes.extractArrayFrom(response.getData(), 
                JsonAttribute.repository, JsonAttribute.discussionCategories);

        return categories.stream()
                .map(JsonObject.class::cast)
                .map(DiscussionCategory::new)
                .toList();
    }
}
