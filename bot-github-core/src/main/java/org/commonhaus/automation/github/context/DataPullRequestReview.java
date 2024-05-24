package org.commonhaus.automation.github.context;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import io.quarkus.logging.Log;
import io.smallrye.graphql.client.Response;

public class DataPullRequestReview extends DataCommonObject {
    static final String REVIEW_FIELDS = DataCommonObject.COMMON_OBJECT_MIN + """
            state
            submittedAt
            """;

    public final DataActor author;
    public final String state;
    public final Date submittedAt;

    DataPullRequestReview(JsonObject object) {
        super(object);
        this.author = JsonAttribute.author.actorFrom(object);
        this.state = JsonAttribute.state.stringFrom(object);
        this.submittedAt = JsonAttribute.submittedAt.dateFrom(object);
    }

    public String toString() {
        return String.format("Review [%s] by %s", this.state, this.author);
    }

    static List<DataPullRequestReview> queryReviews(QueryContext qc,
            String pullRequestId) {
        if (qc.hasErrors()) {
            return List.of();
        }
        Log.debugf("[%s] queryReviews for pull request %s", qc.getLogId(), pullRequestId);
        List<DataPullRequestReview> prReviews = new ArrayList<>();
        Map<String, Object> variables = new HashMap<>();
        variables.put("id", pullRequestId);

        JsonObject pageInfo;
        String cursor = null;

        // paginated...
        do {
            variables.put("after", cursor);
            Response response = qc.execRepoQuerySync("""
                    query($id: ID!, $after: String) {
                        node(id: $id) {
                            ... on PullRequest {
                                latestReviews(first: 100, after: $after) {
                                    nodes {
                                        """ + REVIEW_FIELDS + """
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
            if (response.hasError()) {
                qc.clearNotFound();
                break;
            }
            JsonObject latestReviews = JsonAttribute.latestReviews.extractObjectFrom(response.getData(), JsonAttribute.node);

            JsonArray nodes = JsonAttribute.nodes.jsonArrayFrom(latestReviews);
            prReviews.addAll(nodes.stream()
                    .map(JsonObject.class::cast)
                    .map(DataPullRequestReview::new)
                    .toList());

            pageInfo = JsonAttribute.pageInfo.jsonObjectFrom(latestReviews);
            cursor = JsonAttribute.endCursor.stringFrom(pageInfo);
        } while (JsonAttribute.hasNextPage.booleanFromOrFalse(pageInfo));
        return prReviews;
    }
}
