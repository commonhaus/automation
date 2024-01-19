package org.commonhaus.automation.github.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import org.commonhaus.automation.github.CFGHQueryHelper.RepoQuery;

import io.quarkus.logging.Log;
import io.smallrye.graphql.client.Response;

public class Label extends CommonType {

    public final String color;
    public final boolean isDefault;
    public final String description;
    public final String name;
    public final String url;

    public Label(JsonObject object) {
        super(object);
        this.color = JsonAttribute.color.stringFrom(object);
        this.isDefault = JsonAttribute.isDefault.booleanFromOrFalse(object);
        this.description = JsonAttribute.description.stringFrom(object);
        this.name = JsonAttribute.name.stringFrom(object);
        this.url = JsonAttribute.url.stringFrom(object);
    }

    public String toString() {
        return String.format("Label [%s] %s", this.id, this.name);
    }

    public static List<Label> queryLabels(RepoQuery queryContext, String labeledId) {
        if (queryContext.hasErrors()) {
            return List.of();
        }
        Log.debugf("queryLabels for labelable %s", labeledId);
        List<Label> labels = new ArrayList<>();
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", labeledId);

        JsonObject pageInfo = null;
        String cursor = null;

        do {
            variables.put("after", cursor);
            Response response = queryContext.execRepoQuerySync("""
                        query($id: ID!, $after: String) {
                            node(id: $id) {
                                ... on Labelable {
                                    labels(first: 100, after: $after) {
                                        nodes {
                                            color
                                            description
                                            id
                                            isDefault
                                            name
                                            url
                                        }
                                        pageInfo {
                                            hasNextPage
                                            endCursor
                                        }
                                    }
                                }
                            }
                        }
                    """, variables);
            Log.debugf("labels (%s): %s", cursor, response.getData());
            if (response.hasError()) {
                break;
            }
            JsonObject allLabels = JsonAttribute.labels.extractObjectFrom(response.getData(),
                    JsonAttribute.node);

            JsonArray nodes = JsonAttribute.nodes.jsonArrayFrom(allLabels);
            labels.addAll(nodes.stream()
                    .map(JsonObject.class::cast)
                    .map(Label::new)
                    .toList());

            pageInfo = JsonAttribute.pageInfo.jsonObjectFrom(allLabels);
            cursor = JsonAttribute.endCursor.stringFrom(pageInfo);
        } while (pageInfo != null && JsonAttribute.hasNextPage.booleanFromOrFalse(pageInfo));
        return labels;
    }
}
