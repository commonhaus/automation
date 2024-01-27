package org.commonhaus.automation.github.model;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import org.commonhaus.automation.github.QueryHelper.QueryContext;
import org.kohsuke.github.GHLabel;

import io.quarkus.logging.Log;
import io.smallrye.graphql.client.Response;

public class DataLabel extends DataCommonType {

    static final String LABEL_FIELDS = """
                color
                description
                id
                isDefault
                name
                url
            """;

    static final String FIRST_10_LABELS = """
            labels(first: 10) {
                nodes {
                """ + LABEL_FIELDS + """
                        }
                    }
                }
            """;

    public final String color;
    public final boolean isDefault;
    public final String description;
    public final String name;
    public final String url;

    public DataLabel(JsonObject object) {
        super(object);
        this.color = JsonAttribute.color.stringFrom(object);
        this.isDefault = JsonAttribute.isDefault.booleanFromOrFalse(object);
        this.description = JsonAttribute.description.stringFrom(object);
        this.name = JsonAttribute.name.stringFrom(object);
        this.url = JsonAttribute.url.stringFrom(object);
    }

    public DataLabel(GHLabel ghLabel) {
        super(ghLabel.getNodeId());
        this.color = ghLabel.getColor();
        this.isDefault = ghLabel.isDefault();
        this.description = ghLabel.getDescription();
        this.name = ghLabel.getName();
        this.url = ghLabel.getUrl();
    }

    public DataLabel(Builder builder) {
        super(builder.id);
        this.color = builder.color;
        this.isDefault = builder.isDefault;
        this.description = builder.description;
        this.name = builder.name;
        this.url = builder.url;
    }

    public String toString() {
        return String.format("Label [%s] %s", this.id, this.name);
    }

    public static class Builder {
        private String id;
        private String color;
        private boolean isDefault;
        private String description;
        private String name;
        private String url;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder color(String color) {
            this.color = color;
            return this;
        }

        public Builder isDefault(boolean isDefault) {
            this.isDefault = isDefault;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public DataLabel build() {
            return new DataLabel(this);
        }
    }

    /**
     * Do not use this directly: go through methods on QueryContext.
     *
     * Use a graphQL Query to find all labels assigned to a given
     * label-able (issue, pull request, discussion, etc.).
     *
     * Exceptions or errors are captured in the queryContext (this method will not throw)
     *
     * @param queryContext Context for the query (client authentication, etc.)
     * @param labeledId ID of the labelable (obscure string, not a number)
     * @return list of {@link DataLabel} (may return empty list, never null)
     */
    public static Set<DataLabel> queryLabels(QueryContext queryContext, String labeledId) {
        if (queryContext.hasErrors()) {
            return null;
        }
        boolean repositoryQuery = false;
        String query = """
                query($id: ID!, $after: String) {
                    node(id: $id) {
                        ... on Labelable {
                            labels(first: 100, after: $after) {
                                nodes {
                                """ + LABEL_FIELDS + """
                                    }
                                    pageInfo {
                                        hasNextPage
                                        endCursor
                                    }
                                }
                            }
                        }
                    }
                """;

        // A Repository has an id, and it has labels, but it is not a Labelable.
        // we need to alter the query a little bit to get the labels.
        if (labeledId.equals(queryContext.getEventData().getRepositoryId())) {
            repositoryQuery = true;
            query = """
                query($owner: String!, $name: String!, $after: String) {
                    repository(owner: $owner, name: $name) {
                      labels(first: 100, after: $after) {
                        nodes {
                          id
                          name
                          color
                          description
                        }
                        pageInfo {
                          hasNextPage
                          endCursor
                        }
                      }
                    }
                  }
            """;
        }

        Log.debugf("queryLabels for labelable %s (repo=%s)", labeledId, repositoryQuery);

        Set<DataLabel> labels = new HashSet<>();
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", labeledId);

        JsonObject pageInfo = null;
        String cursor = null;

        do {
            variables.put("after", cursor);
            Response response = queryContext.execRepoQuerySync(query, variables);
            Log.debugf("labels (%s): %s", cursor, response.getData());
            if (response.hasError()) {
                break;
            }
            JsonObject pageLabels = repositoryQuery
                ? JsonAttribute.labels.extractObjectFrom(response.getData(), JsonAttribute.repository)
                : JsonAttribute.labels.extractObjectFrom(response.getData(), JsonAttribute.node);

            JsonArray nodes = JsonAttribute.nodes.jsonArrayFrom(pageLabels);
            labels.addAll(nodes.stream()
                    .map(JsonObject.class::cast)
                    .map(DataLabel::new)
                    .toList());

            pageInfo = JsonAttribute.pageInfo.jsonObjectFrom(pageLabels);
            cursor = JsonAttribute.endCursor.stringFrom(pageInfo);
        } while (pageInfo != null && JsonAttribute.hasNextPage.booleanFromOrFalse(pageInfo));
        return labels;
    }

    public static void modifyLabels(QueryContext queryContext, String labeledId, List<DataLabel> newLabels) {
        if (queryContext.hasErrors()) {
            return;
        }
        Log.debugf("modifyLabels for labelable %s with %s", labeledId, newLabels);
        String[] labelIds = newLabels.stream().map(l -> l.id).toArray(String[]::new);

        Map<String, Object> variables = new HashMap<>();
        variables.put("labelableId", labeledId);
        variables.put("labelIds", labelIds);

        Response response = queryContext.execRepoQuerySync("""
                mutation AddLabels($labelableId: ID!, $labelIds: [ID!]!) {
                  addLabelsToLabelable(input: {
                    labelableId: $labelableId,
                    labelIds: $labelIds}) {
                        clientMutationId
                    }
                }""", variables);
        Log.debugf("modifyLabels response: %s", response == null ? "null (dryRun?)" : response.getData());
        if (response.hasError()) {
            return;
        }
        // we should get a separate/follow-on webhook event for the label change
        // let that happen, rather than racing.
    }
}
