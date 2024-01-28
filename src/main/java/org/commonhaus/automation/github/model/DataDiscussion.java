package org.commonhaus.automation.github.model;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import org.commonhaus.automation.github.QueryHelper.QueryContext;

import io.quarkus.logging.Log;
import io.smallrye.graphql.client.Response;

public class DataDiscussion extends DataCommonItem {

    static final String DISCUSSION_FIELDS = """
            id
            number
            title
            category {
                """ + DataDiscussionCategory.DISCUSSION_CATEGORY_FIELDS + """
            }
            author {
                avatarUrl
                login
                url
            }
            authorAssociation
            activeLockReason
            answerChosenAt
            body
            bodyText
            """ + DataLabel.FIRST_10_LABELS + """
            closed
            closedAt
            createdAt
            isAnswered
            locked
            updatedAt
            url
                """;

    public final DataDiscussionCategory category;
    public final List<DataLabel> labels;

    public final boolean isAnswered;
    public final Date answerChosenAt;

    public final Integer upvoteCount;

    DataDiscussion(JsonObject object) {
        super(object);

        this.category = JsonAttribute.category.discussionCategoryFrom(object);
        this.labels = JsonAttribute.labels.labelsFrom(object);
        this.isAnswered = JsonAttribute.isAnswered.booleanFromOrFalse(object);
        this.answerChosenAt = JsonAttribute.answerChosenAt.dateFrom(object);
        this.upvoteCount = JsonAttribute.upvoteCount.integerFrom(object);
    }

    public String toString() {
        return String.format("Discussion [%s] %s", this.id, this.title);
    }

    /**
     * Exceptions and errors are captured for caller in the queryContext
     *
     * @param queryContext
     * @param isOpen true for open discussions, false for non-open discussions
     * @return list of discussions
     */
    public static List<DataDiscussion> queryDiscussions(QueryContext queryContext, boolean isOpen) {
        if (queryContext.hasErrors()) {
            return List.of();
        }
        List<DataDiscussion> discussions = new ArrayList<>();
        Map<String, Object> variables = new HashMap<>();
        variables.put("isOpen", isOpen);

        JsonObject pageInfo = null;
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
            Log.debugf("discussions (%s): %s", cursor, response.getData());
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
        } while (pageInfo != null && JsonAttribute.hasNextPage.booleanFromOrFalse(pageInfo));
        return discussions;
    }

    /**
     * Edit discussion body
     *
     * @return list of discussion categories
     */
    public static DataDiscussion editDiscussion(QueryContext queryContext, DataDiscussion discussion, String modifiedText) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("discussionId", discussion.id);
        variables.put("comment", modifiedText);

        Response response = queryContext.execRepoQuerySync("""
                mutation {
                    updateDiscussion(input: {repositoryId: "1234", categoryId: "5678", body: "The body", title: "The title"}) {
                        discussion {
                            """ + DISCUSSION_FIELDS + """
                        }
                    }
                }
                """,
                variables);
        Log.debugf("Discussion #%s: add comment, result: %s", discussion.number, response.getData());
        if (response.hasError()) {
            Log.errorf("Discussion #%s - Unable to add comment", discussion.number);
            return null;
        }
        return JsonAttribute.discussion.discussionFrom(response.getData());
    }

    /**
     * Exceptions and errors are captured for caller in the queryContext
     */
    public static DataDiscussionComment addComment(QueryContext queryContext, DataDiscussion discussion, String markdownText) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("discussionId", discussion.id);
        variables.put("comment", markdownText);

        Response response = queryContext.execRepoQuerySync("""
                mutation($discussionId: ID!, $comment: String!) {
                    addDiscussionComment(input: {discussionId: $discussionId, body: $comment}) {
                        comment {
                            """ + DataDiscussionComment.DISCUSSION_COMMENT_WITH_REPLY_FIELDS + """
                        }
                    }
                }
                """, variables);
        Log.debugf("Discussion #%s: add comment, result: %s", discussion.number, response.getData());
        if (response.hasError()) {
            Log.errorf("Discussion #%s - Unable to add comment", discussion.number);
            return null;
        }
        JsonObject result = JsonAttribute.addDiscussionComment.jsonObjectFrom(response.getData());

        return JsonAttribute.comment.discussionCommentFrom(result);
    }
}
