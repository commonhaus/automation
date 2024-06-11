package org.commonhaus.automation.github.context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import io.quarkus.qute.TemplateData;
import io.smallrye.graphql.client.Response;

@TemplateData
public class DataDiscussion extends DataCommonItem {

    static final String DISCUSSION_FIELDS = ISSUE_FIELDS + """
            category {
                """ + DataDiscussionCategory.DISCUSSION_CATEGORY_FIELDS + """
            }
            """ + DataLabel.FIRST_10_LABELS + """
            createdAt
            updatedAt
                """;

    public final DataDiscussionCategory category;
    public final List<DataLabel> labels;

    DataDiscussion(JsonObject object) {
        super(object);

        this.category = JsonAttribute.category.discussionCategoryFrom(object);
        this.labels = JsonAttribute.labels.labelsFrom(object);
    }

    public String toString() {
        return "Discussion [%s] %s".formatted(this.id, this.title);
    }

    public static List<DataDiscussion> findDiscussionsWithLabel(QueryContext qc,
            String labelName) {
        List<DataDiscussion> allDiscussions = new ArrayList<>();
        Map<String, Object> variables = new HashMap<>();

        variables.put("query", String.format("repo:%s label:%s sort:updated-desc",
                qc.getRepository().getFullName(), labelName));

        JsonObject pageInfo;
        String cursor = null;
        do {
            variables.put("after", cursor);
            Response response = qc.execRepoQuerySync("""
                    query($query: String!, $after: String) {
                        search(query: $query, type: DISCUSSION, first: 100, after: $after) {
                            pageInfo {
                                endCursor
                                hasNextPage
                            }
                            nodes {
                                ... on Discussion {
                                    """ + DISCUSSION_FIELDS + """
                                }
                            }
                        }
                    }
                        """, variables);
            if (response.hasError()) {
                break;
            }

            JsonObject search = JsonAttribute.search.jsonObjectFrom(response.getData());
            JsonArray nodes = JsonAttribute.nodes.jsonArrayFrom(search);
            allDiscussions.addAll(nodes.stream()
                    .map(JsonObject.class::cast)
                    .map(DataDiscussion::new)
                    .toList());

            pageInfo = JsonAttribute.pageInfo.jsonObjectFrom(search);
            cursor = JsonAttribute.endCursor.stringFrom(pageInfo);
        } while (JsonAttribute.hasNextPage.booleanFromOrFalse(pageInfo));
        return allDiscussions;
    }

    static DataDiscussion queryDiscussion(QueryContext qc, String nodeId) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", nodeId);

        Response response = qc.execQuerySync("""
                query($id: ID!) {
                    node(id: $id) {
                        ... on Discussion {
                            """ + DISCUSSION_FIELDS + """
                        }
                    }
                }
                """, variables);
        if (response.hasError()) {
            qc.clearNotFound();
            return null;
        }
        return new DataDiscussion(JsonAttribute.node.jsonObjectFrom(response.getData()));
    }

    /**
     * package private. See QueryHelper / QueryContext
     */
    static DataDiscussion editDiscussion(QueryContext qc, String nodeId,
            String modifiedText, String fields) {

        fields = fields == null ? DISCUSSION_FIELDS : fields;

        Map<String, Object> variables = new HashMap<>();
        variables.put("id", nodeId);
        variables.put("body", modifiedText);

        Response response = qc.execRepoQuerySync("""
                mutation UpdateDiscussion($id: ID!, $body: String!) {
                    updateDiscussion(input: { discussionId: $id, body: $body }) {
                        clientMutationId
                        discussion {
                            """ + DISCUSSION_FIELDS + """
                        }
                    }
                }
                """,
                variables);
        if (response.hasError()) {
            qc.clearNotFound();
            return null;
        }
        return JsonAttribute.discussion.discussionFrom(response.getData());
    }
}
