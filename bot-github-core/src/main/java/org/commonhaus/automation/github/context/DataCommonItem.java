package org.commonhaus.automation.github.context;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.Nonnull;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import io.quarkus.qute.TemplateData;
import io.smallrye.graphql.client.Response;

@TemplateData
public class DataCommonItem extends DataCommonObject {

    public static final String ISSUE_FIELDS = COMMON_OBJECT_FIELDS + """
            number
            title
            body
            closed
            closedAt
            """.stripIndent();

    public static final String PR_FIELDS = ISSUE_FIELDS + """
            reviewDecision
            """.stripIndent();

    public static final String ISSUE_FIELDS_MIN = COMMON_OBJECT_MIN + """
            number
            title
            """.stripIndent();

    public static final String PR_FIELDS_MIN = ISSUE_FIELDS_MIN + """
            reviewDecision
            """.stripIndent();

    // @formatter:off
    static final String CREATE_ISSUE = """
            mutation($title: String!, $body: String!, $repositoryId: ID!, $labelIds: [ID!]) {
                createIssue(input: {
                    repositoryId: $repositoryId,
                    title: $title,
                    body: $body,
                    labelIds: $labelIds
                }) {
                    clientMutationId
                    issue {
                        """ + ISSUE_FIELDS + """
                    }
                }
            }
            """.stripIndent();

    static final String EDIT_ISSUE_DESCRIPTION = """
            mutation($id: ID!, $body: String!) {
                updateIssue(input: {
                    id: $id,
                    body: $body
                }) {
                    clientMutationId
                    issue {
                        %s
                    }
                }
            }
            """.stripIndent();

    static final String EDIT_PR_DESCRIPTION = """
            mutation($id: ID!, $body: String!) {
                updatePullRequest(input: {
                    pullRequestId: $id,
                    body: $body
                }) {
                    clientMutationId
                    pullRequest {
                        %s
                    }
                }
            }
            """.stripIndent();

    static final String QUERY_ISSUES_WITH_LABEL = """
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
            """.stripIndent();

    static final String QUERY_ITEM_BY_NODE = """
            query($id: ID!) {
                node(id: $id) {
                    ... on Issue {
                        """ + ISSUE_FIELDS + """
                    }
                    ... on PullRequest {
                        """ + PR_FIELDS + """
                    }
                }
            }
            """.stripIndent();
    // @formatter:on

    /** Issue/Discussion/PR number within repository */
    public final Integer number;
    public final String title;

    // Closable
    public final Instant closedAt;
    public final String state;
    public final boolean closed;
    public final boolean isPullRequest;

    DataCommonItem(JsonObject object) {
        super(object);

        this.number = JsonAttribute.number.integerFrom(object);
        this.title = JsonAttribute.title.stringFrom(object);

        this.state = JsonAttribute.state.stringFrom(object);
        this.closedAt = JsonAttribute.closedAt.instantFrom(object);
        this.closed = JsonAttribute.closed.booleanFromOrDefault(object,
                state != null && state.equalsIgnoreCase("closed"));

        this.isPullRequest = JsonAttribute.reviewDecision.existsIn(object);
    }

    public static DataCommonItem createIssue(GitHubQueryContext qc, String title, String body, Collection<DataLabel> labels) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("title", title);
        variables.put("body", body);
        variables.put("repositoryId", qc.getRepository().getNodeId());
        if (labels != null && !labels.isEmpty()) {
            variables.put("labelIds", labels.stream().map(x -> x.id).toList());
        }

        Response response = qc.execQuerySync(CREATE_ISSUE, variables);
        if (qc.hasErrors() || response == null) {
            qc.checkRemoveNotFound();
            return null;
        }
        JsonObject result = JsonAttribute.createIssue.jsonObjectFrom(response.getData());
        return JsonAttribute.issue.commonItemFrom(result);
    }

    public static DataCommonItem editIssueDescription(GitHubQueryContext qc,
            String nodeId, String bodyString, String issueFields) {

        issueFields = issueFields == null ? ISSUE_FIELDS_MIN : issueFields;

        Map<String, Object> variables = new HashMap<>();
        variables.put("id", nodeId);
        variables.put("body", bodyString);

        Response response = qc.execQuerySync(EDIT_ISSUE_DESCRIPTION.formatted(issueFields),
                variables);
        if (qc.hasErrors() || response == null) {
            qc.checkRemoveNotFound();
            return null;
        }
        JsonObject result = JsonAttribute.updateIssue.jsonObjectFrom(response.getData());
        return JsonAttribute.issue.commonItemFrom(result);
    }

    public static DataCommonItem editPullRequestDescription(GitHubQueryContext qc,
            String nodeId, String bodyString, String prFields) {

        prFields = prFields == null ? PR_FIELDS_MIN : prFields;

        Map<String, Object> variables = new HashMap<>();
        variables.put("id", nodeId);
        variables.put("body", bodyString);

        Response response = qc.execQuerySync(EDIT_PR_DESCRIPTION.formatted(prFields), variables);
        if (qc.hasErrors() || response == null) {
            qc.checkRemoveNotFound();
            return null;
        }
        JsonObject result = JsonAttribute.updatePullRequest.jsonObjectFrom(response.getData());
        return JsonAttribute.pullRequest.commonItemFrom(result);
    }

    @Nonnull
    public static List<DataCommonItem> findIssuesWithLabel(GitHubQueryContext qc,
            String labelName) {
        List<DataCommonItem> allIssues = new ArrayList<>();
        Map<String, Object> variables = new HashMap<>();

        variables.put("query", String.format("repo:%s label:%s sort:updated-desc",
                qc.getRepository().getFullName(), labelName));

        DataPageInfo pageInfo = new DataPageInfo(null, false);

        do {
            variables.put("after", pageInfo.cursor());
            Response response = qc.execRepoQuerySync(QUERY_ISSUES_WITH_LABEL, variables);
            if (qc.hasErrors()) {
                break;
            }

            JsonObject search = JsonAttribute.search.jsonObjectFrom(response.getData());
            JsonArray nodes = JsonAttribute.nodes.jsonArrayFrom(search);
            allIssues.addAll(nodes.stream()
                    .map(JsonObject.class::cast)
                    .map(DataCommonItem::new)
                    .toList());

            pageInfo = JsonAttribute.pageInfo.pageInfoFrom(search);
        } while (pageInfo.hasNextPage());
        return allIssues;
    }

    public static DataCommonItem queryItem(GitHubQueryContext qc, String nodeId) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", nodeId);

        Response response = qc.execQuerySync(QUERY_ITEM_BY_NODE, variables);
        if (qc.hasErrors() || response == null) {
            qc.checkRemoveNotFound();
            return null;
        }
        return JsonAttribute.node.commonItemFrom(response.getData());
    }
}
