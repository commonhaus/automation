package org.commonhaus.automation.github.context;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import io.quarkus.qute.TemplateData;
import io.smallrye.graphql.client.Response;

@TemplateData
public class DataCommonItem extends DataCommonObject {

    static final String ISSUE_FIELDS = COMMON_OBJECT_FIELDS + """
            number
            title
            body
            closed
            closedAt
            """;

    static final String PR_FIELDS = ISSUE_FIELDS + """
            reviewDecision
            """;

    static final String ISSUE_FIELDS_MIN = COMMON_OBJECT_MIN + """
            number
            title
            """;

    static final String PR_FIELDS_MIN = ISSUE_FIELDS_MIN + """
            reviewDecision
            """;

    /** Issue/Discussion/PR number within repository */
    public final Integer number;
    public final String title;

    // Closable
    public final Date closedAt;
    public final boolean closed;
    public final boolean isPullRequest;

    DataCommonItem(JsonObject object) {
        super(object);

        this.number = JsonAttribute.number.integerFrom(object);
        this.title = JsonAttribute.title.stringFrom(object);

        this.closedAt = JsonAttribute.closedAt.dateFrom(object);
        this.closed = JsonAttribute.closed.booleanFromOrFalse(object);

        this.isPullRequest = JsonAttribute.reviewDecision.existsIn(object);
    }

    public static DataCommonItem editIssueDescription(QueryContext queryContext,
            String nodeId, String bodyString) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", nodeId);
        variables.put("body", bodyString);

        Response response = queryContext.execQuerySync("""
                mutation($id: ID!, $body: String!) {
                    updateIssue(input: {
                        id: $id,
                        body: $body
                    }) {
                        clientMutationId
                        issue {
                            """ + ISSUE_FIELDS_MIN + """
                        }
                    }
                }
                """, variables);
        if (response.hasError()) {
            if (queryContext.hasNotFound()) {
                queryContext.clearErrors();
            }
            return null;
        }
        JsonObject result = JsonAttribute.updateIssue.jsonObjectFrom(response.getData());
        return JsonAttribute.pullRequest.commonItemFrom(result);
    }

    public static DataCommonItem editPullRequestDescription(QueryContext queryContext,
            String nodeId, String bodyString) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", nodeId);
        variables.put("body", bodyString);

        Response response = queryContext.execQuerySync("""
                mutation($id: ID!, $body: String!) {
                    updatePullRequest(input: {
                        pullRequestId: $id,
                        body: $body
                    }) {
                        clientMutationId
                        pullRequest {
                            """ + PR_FIELDS_MIN + """
                        }
                    }
                }
                """, variables);
        if (response.hasError()) {
            if (queryContext.hasNotFound()) {
                queryContext.clearErrors();
            }
            return null;
        }
        JsonObject result = JsonAttribute.updatePullRequest.jsonObjectFrom(response.getData());
        return JsonAttribute.pullRequest.commonItemFrom(result);
    }

    public static List<DataCommonItem> findIssuesWithLabel(QueryContext queryContext,
            String labelName) {
        List<DataCommonItem> allIssues = new ArrayList<>();
        Map<String, Object> variables = new HashMap<>();

        variables.put("query", String.format("repo:%s label:%s sort:updated-desc",
                queryContext.getRepository().getFullName(), labelName));

        JsonObject pageInfo;
        String cursor = null;
        do {
            variables.put("after", cursor);
            Response response = queryContext.execRepoQuerySync("""
                    query($query: String!, $after: String) {
                        search(query: $query, type: ISSUE, first: 100, after: $after) {
                            pageInfo {
                                endCursor
                                hasNextPage
                            }
                            nodes {
                                ... on Issue {
                                    """ + ISSUE_FIELDS + """
                    }
                    ... on PullRequest {
                        """ + PR_FIELDS + """
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
            allIssues.addAll(nodes.stream()
                    .map(JsonObject.class::cast)
                    .map(DataCommonItem::new)
                    .toList());

            pageInfo = JsonAttribute.pageInfo.jsonObjectFrom(search);
            cursor = JsonAttribute.endCursor.stringFrom(pageInfo);
        } while (JsonAttribute.hasNextPage.booleanFromOrFalse(pageInfo));
        return allIssues;
    }
}
