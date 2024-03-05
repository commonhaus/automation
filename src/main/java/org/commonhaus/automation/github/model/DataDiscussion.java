package org.commonhaus.automation.github.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import org.commonhaus.automation.github.model.QueryHelper.QueryContext;

import io.smallrye.graphql.client.Response;

public class DataDiscussion extends DataCommonItem {

    static final String DISCUSSION_FIELDS = ISSUE_FIELDS + """
            category {
                """ + DataDiscussionCategory.DISCUSSION_CATEGORY_FIELDS + """
            }
            authorAssociation
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

    /** package private. See QueryHelper / QueryContext */
    static List<DataDiscussion> queryDiscussions(QueryContext queryContext, boolean isOpen) {
        List<DataDiscussion> discussions = new ArrayList<>();
        Map<String, Object> variables = new HashMap<>();
        variables.put("isOpen", isOpen);

        JsonObject pageInfo;
        String cursor = null;
        do {
            variables.put("after", cursor);
            Response response = queryContext.execRepoQuerySync("""
                    query($name: String!, $owner: String!, $after: String) {
                        repository(owner: $owner, name: $name) {
                          discussions(first: 50, after: $after, orderBy: {field: UPDATED_AT, direction: DESC}) {
                            nodes {
                                """ + DISCUSSION_FIELDS + """
                            }
                          }
                        }
                      }
                    """, variables);
            if (response.hasError()) {
                break;
            }
            JsonObject allDiscussions = JsonAttribute.discussions.extractObjectFrom(response.getData(),
                    JsonAttribute.repository);

            JsonArray nodes = JsonAttribute.nodes.jsonArrayFrom(allDiscussions);
            discussions.addAll(nodes.stream()
                    .map(JsonObject.class::cast)
                    .map(DataDiscussion::new)
                    .toList());

            pageInfo = JsonAttribute.pageInfo.jsonObjectFrom(allDiscussions);
            cursor = JsonAttribute.endCursor.stringFrom(pageInfo);
        } while (JsonAttribute.hasNextPage.booleanFromOrFalse(pageInfo));
        return discussions;
    }

    /**
     * package private. See QueryHelper / QueryContext
     */
    static void editDiscussion(QueryContext queryContext, DataDiscussion discussion, String modifiedText) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", discussion.id);
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
