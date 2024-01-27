package org.commonhaus.automation.github.rules;

import java.util.ArrayList;
import java.util.List;

import org.commonhaus.automation.github.EventData;
import org.commonhaus.automation.github.QueryHelper.QueryContext;
import org.commonhaus.automation.github.model.DataDiscussion;
import org.commonhaus.automation.github.model.DataDiscussionCategory;
import org.commonhaus.automation.github.model.EventPayload;
import org.commonhaus.automation.github.model.EventType;

/**
 * Only applies to GitHub Discussions
 */
public class MatchCategory {
    final static List<EventType> eventsWithCategories = List.of(EventType.discussion, EventType.discussion_comment);
    List<String> category;

    public boolean matches(QueryContext queryContext) {
        EventData eventData = queryContext.getEventData();
        if (eventData == null || !eventsWithCategories.contains(eventData.getEventType())) {
            return false;
        }

        EventPayload.DiscussionPayload payload = eventData.getEventPayload();
        DataDiscussion discussion = payload.discussion;
        if (discussion == null) {
            return false;
        }

        List<String> comparison = new ArrayList<>();
        DataDiscussionCategory discussionCategory = discussion.category;
        addIfPresent(comparison, discussionCategory.name);
        addIfPresent(comparison, discussionCategory.slug);
        addIfPresent(comparison, discussionCategory.id);

        return category.stream().anyMatch(x -> comparison.contains(x.toLowerCase()));
    }

    private void addIfPresent(List<String> list, String value) {
        if (value != null) {
            list.add(value.toLowerCase());
        }
    }
}
