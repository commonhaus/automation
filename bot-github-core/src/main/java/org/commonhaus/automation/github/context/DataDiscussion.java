package org.commonhaus.automation.github.context;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.Nonnull;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import io.quarkus.qute.TemplateData;
import io.smallrye.graphql.client.Response;

@TemplateData
public class DataDiscussion extends DataCommonItem {

    // @formatter:off
    static final String DISCUSSION_FIELDS = ISSUE_FIELDS + """
            category {
                """ + DataDiscussionCategory.DISCUSSION_CATEGORY_FIELDS + """
            }
            """ + DataLabel.FIRST_10_LABELS + """
            createdAt
            updatedAt
                """.stripIndent();

    private static final String QUERY_DISCUSSIONS_WITH_LABEL = """
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
            """.stripIndent();

    private static final String QUERY_DISCUSSION_BY_ID = """
            query($id: ID!) {
                node(id: $id) {
                    ... on Discussion {
                        """ + DISCUSSION_FIELDS + """
                    }
                }
            }
            """.stripIndent();

    private static final String UPDATE_DISCUSSION = """
            mutation UpdateDiscussion($id: ID!, $body: String!) {
                updateDiscussion(input: { discussionId: $id, body: $body }) {
                    clientMutationId
                    discussion {
                        """ + DISCUSSION_FIELDS + """
                    }
                }
            }
            """.stripIndent();
    // @formatter:on

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

    @Nonnull
    public static List<DataDiscussion> findDiscussionsWithLabel(GitHubQueryContext qc,
            String labelName) {
        List<DataDiscussion> allDiscussions = new ArrayList<>();
        Map<String, Object> variables = new HashMap<>();

        variables.put("query", "repo:%s label:%s sort:updated-desc".formatted(
                qc.getRepository().getFullName(), labelName));

        DataPageInfo pageInfo = new DataPageInfo(null, false);
        do {
            variables.put("after", pageInfo.cursor());
            Response response = qc.execRepoQuerySync(QUERY_DISCUSSIONS_WITH_LABEL, variables);
            if (qc.hasErrors()) {
                qc.checkRemoveNotFound();
                return null;
            }

            JsonObject search = JsonAttribute.search.jsonObjectFrom(response.getData());
            JsonArray nodes = JsonAttribute.nodes.jsonArrayFrom(search);
            allDiscussions.addAll(nodes.stream()
                    .map(JsonObject.class::cast)
                    .map(DataDiscussion::new)
                    .toList());

            pageInfo = JsonAttribute.pageInfo.pageInfoFrom(search);
        } while (pageInfo.hasNextPage());
        return allDiscussions;
    }

    @Nonnull
    public static List<DataDiscussion> findDiscussionsBetween(GitHubQueryContext qc,
            LocalDate from, LocalDate toExclusive) {
        List<DataDiscussion> allDiscussions = new ArrayList<>();
        Map<String, Object> variables = new HashMap<>();

        variables.put("query", "repo:%s is:discussion created:%s..%s".formatted(
                qc.getRepository().getFullName(), from, toExclusive.minusDays(1)));

        DataPageInfo pageInfo = new DataPageInfo(null, false);
        do {
            variables.put("after", pageInfo.cursor());
            Response response = qc.execRepoQuerySync(QUERY_DISCUSSIONS_WITH_LABEL, variables);
            if (qc.hasErrors()) {
                qc.checkRemoveNotFound();
                return null;
            }

            JsonObject search = JsonAttribute.search.jsonObjectFrom(response.getData());
            JsonArray nodes = JsonAttribute.nodes.jsonArrayFrom(search);
            allDiscussions.addAll(nodes.stream()
                    .map(JsonObject.class::cast)
                    .map(DataDiscussion::new)
                    .toList());

            pageInfo = JsonAttribute.pageInfo.pageInfoFrom(search);
        } while (pageInfo.hasNextPage());
        return allDiscussions;
    }

    static DataDiscussion queryDiscussion(GitHubQueryContext qc, String nodeId) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", nodeId);

        Response response = qc.execQuerySync(QUERY_DISCUSSION_BY_ID, variables);
        if (qc.hasErrors() || response == null) {
            qc.checkRemoveNotFound();
            return null;
        }
        return new DataDiscussion(JsonAttribute.node.jsonObjectFrom(response.getData()));
    }

    /**
     * package private. See QueryHelper / QueryContext
     */
    static DataDiscussion editDiscussion(GitHubQueryContext qc, String nodeId,
            String modifiedText, String fields) {

        fields = fields == null ? DISCUSSION_FIELDS : fields;

        Map<String, Object> variables = new HashMap<>();
        variables.put("id", nodeId);
        variables.put("body", modifiedText);

        Response response = qc.execRepoQuerySync(UPDATE_DISCUSSION, variables);
        if (qc.hasErrors()) {
            qc.checkRemoveNotFound();
            return null;
        }
        return JsonAttribute.discussion.discussionFrom(response.getData());
    }
}
