package org.commonhaus.automation.github.model;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import org.commonhaus.automation.github.model.QueryHelper.QueryContext;
import org.kohsuke.github.GHLabel;

import io.quarkus.logging.Log;
import io.smallrye.graphql.client.Response;

public class DataLabel extends DataCommonType {

    static final String LABEL_FIELDS = """
                id
                name
            """;
    static final String FIRST_10_LABELS = """
            labels(first: 10) {
                nodes {
                """ + LABEL_FIELDS + """
                    }
                }
            """;
    static final String PAGINATED_LABELS = """
            labels(first: 50, after: $after) {
                nodes {
                """ + LABEL_FIELDS + """
                }
                pageInfo {
                    hasNextPage
                    endCursor
                }
            }
            """;

    public final String name;

    public DataLabel(JsonObject object) {
        super(object);
        this.name = JsonAttribute.name.stringFrom(object);
    }

    public DataLabel(GHLabel ghLabel) {
        super(ghLabel.getNodeId());
        this.name = ghLabel.getName();
    }

    public DataLabel(Builder builder) {
        super(builder.id);
        this.name = builder.name;
    }

    public String toString() {
        return String.format("Label[%s:%s]", this.id, this.name);
    }

    public static class Builder {
        private String id;
        private String name;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public DataLabel build() {
            if (id == null) {
                id = name;
            }
            return new DataLabel(this);
        }
    }

    /** package private. See QueryHelper / QueryContext */
    static Set<DataLabel> queryLabels(QueryContext queryContext, String labeledId) {
        if (queryContext.hasErrors()) {
            Log.debugf("[%s] queryLabels for labelable %s; skipping modify (errors)", queryContext.getLogId(), labeledId);
            return null;
        }
        String query = """
                query($id: ID!, $after: String) {
                    node(id: $id) {
                        ... on Labelable {
                        """ + PAGINATED_LABELS + """
                        }
                    }
                }
                """;

        // A Repository has an id, and it has labels, but it is not a Labelable.
        // we need to alter the query a little bit to get the labels.
        if (labeledId.equals(queryContext.getEventData().getRepositoryId())) {
            query = """
                    query($owner: String!, $name: String!, $after: String) {
                        repository(owner: $owner, name: $name) {
                            """ + PAGINATED_LABELS + """
                        }
                    }
                    """;
        }

        final var repositoryQuery = query.contains("repository");
        Log.debugf("[%s] queryLabels for labelable %s", queryContext.getLogId(), labeledId);

        Set<DataLabel> labels = new HashSet<>();
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", labeledId);

        paginateLabels(queryContext, query, variables, labels, (obj) -> repositoryQuery
                ? JsonAttribute.labels.extractObjectFrom(obj, JsonAttribute.repository)
                : JsonAttribute.labels.extractObjectFrom(obj, JsonAttribute.node));

        Log.infof("[%s] queryLabels for labelable %s; result=%s", queryContext.getLogId(), labeledId, labels);
        return labels;
    }

    /** package private. See QueryHelper / QueryContext */
    static Set<DataLabel> addLabels(QueryContext queryContext, String labeledId, Collection<DataLabel> newLabels) {
        Log.debugf("[%s] addLabels for labelable %s with %s", queryContext.getLogId(), labeledId, newLabels);
        String[] labelIds = newLabels.stream().map(l -> l.id).toArray(String[]::new);

        Map<String, Object> variables = new HashMap<>();
        variables.put("labelableId", labeledId);
        variables.put("labelIds", labelIds);

        String query = """
                mutation AddLabels($labelableId: ID!, $labelIds: [ID!]!, $after: String) {
                    addLabelsToLabelable(input: { labelableId: $labelableId, labelIds: $labelIds}) {
                        clientMutationId
                        labelable {
                            """ + PAGINATED_LABELS + """
                        }
                    }
                }""";

        Set<DataLabel> labels = new HashSet<>();
        paginateLabels(queryContext, query, variables, labels, (obj) -> JsonAttribute.labels.extractObjectFrom(obj,
                JsonAttribute.addLabelsToLabelable, JsonAttribute.labelable));

        Log.infof("[%s] modifyLabels for labelable %s; result=%s", queryContext.getLogId(), labeledId, labels);
        return labels;
    }

    static void paginateLabels(QueryContext queryContext, String query, Map<String, Object> variables, Set<DataLabel> labels,
            Function<JsonObject, JsonObject> findPageLabels) {
        JsonObject pageInfo;
        String cursor = null;

        do {
            variables.put("after", cursor);
            Response response = queryContext.execRepoQuerySync(query, variables);
            if (response.hasError()) {
                if (queryContext.hasNotFound()) {
                    queryContext.clearErrors();
                }
                break;
            }
            JsonObject pageLabels = findPageLabels.apply(response.getData());
            JsonArray nodes = JsonAttribute.nodes.jsonArrayFrom(pageLabels);
            if (nodes == null) {
                break;
            }
            labels.addAll(nodes.stream()
                    .map(JsonObject.class::cast)
                    .map(DataLabel::new)
                    .toList());

            pageInfo = JsonAttribute.pageInfo.jsonObjectFrom(pageLabels);
            cursor = JsonAttribute.endCursor.stringFrom(pageInfo);
        } while (JsonAttribute.hasNextPage.booleanFromOrFalse(pageInfo));
    }
}
