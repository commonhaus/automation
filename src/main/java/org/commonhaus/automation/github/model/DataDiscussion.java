package org.commonhaus.automation.github.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import org.commonhaus.automation.github.model.QueryHelper.QueryContext;

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
        return String.format("Discussion [%s] %s", this.id, this.title);
    }

    static List<DataDiscussion> findDiscussionsWithLabel(QueryContext queryContext, String labelName) {
        List<DataDiscussion> allDiscussions = new ArrayList<>();
        Map<String, Object> variables = new HashMap<>();

        variables.put("query", String.format("repo:%s label:%s is:open sort:updated-desc",
                queryContext.getRepository().getFullName(), labelName));

        JsonObject pageInfo;
        String cursor = null;
        do {
            variables.put("after", cursor);
            Response response = queryContext.execRepoQuerySync("""
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

    /**
     * package private. See QueryHelper / QueryContext
     */
    static void editDiscussion(QueryContext queryContext, String nodeId, String modifiedText) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", nodeId);
        variables.put("body", modifiedText);

        Response response = queryContext.execRepoQuerySync("""
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
            if (queryContext.hasNotFound()) {
                queryContext.clearErrors();
            }
            return;
        }
        JsonAttribute.discussion.discussionFrom(response.getData());
    }
}
