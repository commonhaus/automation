package org.commonhaus.automation.github;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.quarkus.logging.Log;
import io.smallrye.graphql.client.Response;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

public class Discussion extends GHItem {

    public final DiscussionCategory category;

    public final boolean isAnswered;
    public final Date answerChosenAt;

    public final Integer upvoteCount;

    Discussion(JsonObject object) {
        super(object);

        this.category = JsonAttribute.category.discussionCategoryFrom(object);
        this.isAnswered = JsonAttribute.isAnswered.booleanFromOrFalse(object);
        this.answerChosenAt = JsonAttribute.answerChosenAt.dateFrom(object);
        this.upvoteCount = JsonAttribute.upvoteCount.integerFrom(object);
    }

    /**
     * Exceptions and errors are captured for caller in the queryContext
     * 
     * @return list of discussion categories
     */
    static List<Discussion> listDiscussions(QueryContext queryContext, boolean isOpen) {
        if (queryContext.hasErrors()) {
            return List.of();
        }
        List<Discussion> discussions = new ArrayList<>();
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
                          id
                          number
                          title
                          category {
                              name
                              id
                              emoji
                          }
                          author {
                              id
                              login
                              url
                              avatarUrl
                          }
                          authorAssociation
                          activeLockReason
                          answerChosenAt
                          body
                          bodyText
                          closed
                          closedAt
                          createdAt
                          isAnswered
                          locked
                          updatedAt
                          url
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
                    .map(Discussion::new)
                    .toList());

            pageInfo = JsonAttribute.pageInfo.jsonObjectFrom(allDiscussions);
            cursor = JsonAttribute.endCursor.stringFrom(pageInfo);
        } while (pageInfo != null && JsonAttribute.hasNextPage.booleanFromOrFalse(pageInfo));
        return discussions;
    }
}
