package org.commonhaus.automation.github.context;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import org.kohsuke.github.GHLabel;

import io.quarkus.logging.Log;
import io.smallrye.graphql.client.Response;

public class DataLabel extends DataCommonType {

    static final String LABEL_FIELDS = """
            id
            name
            """.stripIndent();

    // @formatter:off
    static final String FIRST_10_LABELS = """
            labels(first: 10) {
                nodes {
                    """ + LABEL_FIELDS + """
                }
            }
            """.stripIndent();
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
            """.stripIndent();

    static final String QUERY_ITEM_LABELS = """
            query($id: ID!, $after: String) {
                node(id: $id) {
                    ... on Labelable {
                        """ + PAGINATED_LABELS + """
                    }
                }
            }
            """.stripIndent();

    static final String QUERY_REPO_LABELS = """
            query($owner: String!, $name: String!, $after: String) {
                repository(owner: $owner, name: $name) {
                    """ + PAGINATED_LABELS + """
                }
            }
            """.stripIndent();

    static final String ADD_LABELS = """
            mutation AddLabels($labelableId: ID!, $labelIds: [ID!]!, $after: String) {
                addLabelsToLabelable(input: { labelableId: $labelableId, labelIds: $labelIds}) {
                    clientMutationId
                    labelable {
                        """ + PAGINATED_LABELS + """
                    }
                }
            }
            """.stripIndent();


    static final String REMOVE_LABELS = """
            mutation RemoveLabels($labelableId: ID!, $labelIds: [ID!]!, $after: String) {
                removeLabelsFromLabelable(input: { labelableId: $labelableId, labelIds: $labelIds}) {
                    clientMutationId
                    labelable {
                        """ + PAGINATED_LABELS + """
                    }
                }
            }
            """.stripIndent();

    static final String CREATE_LABEL = """
            mutation CreateLabel($name: String!, $repositoryId: ID!, $color: String!) {
                createLabel(input: { name: $name, repositoryId: $repositoryId, color: $color }) {
                    clientMutationId
                    label {
                        """ + LABEL_FIELDS + """
                    }
                }
            }
            """.stripIndent();
    // @formatter:on

    public final String name;

    DataLabel(JsonObject object) {
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
    static Set<DataLabel> queryLabels(GitHubQueryContext qc, String labeledId) {
        if (qc.hasErrors()) {
            Log.debugf("[%s] queryLabels for labelable %s; skipping modify (errors)", qc.getLogId(), labeledId);
            return null;
        }
        String query = QUERY_ITEM_LABELS;

        // A Repository has an id, and it has labels, but it is not a Labelable.
        // we need to alter the query to get the labels.
        if (labeledId.equals(qc.getRepositoryId())) {
            query = QUERY_REPO_LABELS;
        }

        final var repositoryQuery = query.contains("repository");
        Log.debugf("[%s] queryLabels for labelable %s", qc.getLogId(), labeledId);

        Set<DataLabel> labels = new HashSet<>();
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", labeledId);

        paginateLabels(qc, query, variables, labels, (obj) -> repositoryQuery
                ? JsonAttribute.labels.extractObjectFrom(obj, JsonAttribute.repository)
                : JsonAttribute.labels.extractObjectFrom(obj, JsonAttribute.node));

        Log.infof("[%s] queryLabels for labelable %s; result=%s", qc.getLogId(), labeledId, labels);
        return labels;
    }

    /** package private. See QueryHelper / QueryContext */
    static Set<DataLabel> addLabels(GitHubQueryContext qc, String labeledId,
            Collection<DataLabel> newLabels) {
        if (qc.isDryRun()) {
            return new HashSet<>(newLabels);
        }
        Log.debugf("[%s] addLabels for labelable %s with %s", qc.getLogId(), labeledId, newLabels);
        String[] labelIds = newLabels.stream().map(l -> l.id).toArray(String[]::new);

        Map<String, Object> variables = new HashMap<>();
        variables.put("labelableId", labeledId);
        variables.put("labelIds", labelIds);

        Set<DataLabel> labels = new HashSet<>();
        paginateLabels(qc, ADD_LABELS, variables, labels,
                (obj) -> JsonAttribute.labels.extractObjectFrom(obj,
                        JsonAttribute.addLabelsToLabelable, JsonAttribute.labelable));

        Log.infof("[%s] modifyLabels for labelable %s; result=%s", qc.getLogId(), labeledId, labels);
        return labels;
    }

    /** package private. See QueryHelper / QueryContext */
    static Set<DataLabel> removeLabels(GitHubQueryContext qc, String labeledId,
            Collection<DataLabel> oldLabels) {
        if (qc.isDryRun()) {
            return Set.of();
        }
        Log.debugf("[%s] removeLabels for labelable %s with %s", qc.getLogId(), labeledId, oldLabels);
        String[] labelIds = oldLabels.stream().map(l -> l.id).toArray(String[]::new);

        Map<String, Object> variables = new HashMap<>();
        variables.put("labelableId", labeledId);
        variables.put("labelIds", labelIds);

        Set<DataLabel> labels = new HashSet<>();
        paginateLabels(qc, REMOVE_LABELS, variables, labels,
                (obj) -> JsonAttribute.labels.extractObjectFrom(obj,
                        JsonAttribute.removeLabelsFromLabelable, JsonAttribute.labelable));

        Log.infof("[%s] modifyLabels for labelable %s; result=%s", qc.getLogId(), labeledId, labels);
        return labels;
    }

    static DataLabel createLabel(GitHubQueryContext qc, String repoId, String name, String color) {
        if (qc.isDryRun()) {
            return new Builder().name(name).id(name).build();
        }
        Log.debugf("[%s] createLabel %s", qc.getLogId(), name);

        Map<String, Object> variables = new HashMap<>();
        variables.put("name", name);
        variables.put("repositoryId", repoId);
        variables.put("color", color);
        ;
        Response response = qc.execRepoQuerySync(CREATE_LABEL, variables);
        if (qc.hasErrors()) {
            qc.checkRemoveNotFound();
            return null;
        }
        JsonObject result = JsonAttribute.createLabel.jsonObjectFrom(response.getData());
        return JsonAttribute.label.labelFrom(result);
    }

    static void paginateLabels(GitHubQueryContext qc, String query,
            Map<String, Object> variables, Set<DataLabel> labels,
            Function<JsonObject, JsonObject> findPageLabels) {

        DataPageInfo pageInfo = new DataPageInfo(null, false);

        do {
            variables.put("after", pageInfo.cursor());
            Response response = qc.execRepoQuerySync(query, variables);
            if (qc.hasErrors()) {
                qc.checkRemoveNotFound();
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

            pageInfo = JsonAttribute.pageInfo.pageInfoFrom(pageLabels);
        } while (pageInfo.hasNextPage());
    }
}
