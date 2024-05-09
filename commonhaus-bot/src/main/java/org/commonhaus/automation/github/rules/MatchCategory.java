package org.commonhaus.automation.github.rules;

import java.util.List;

import org.commonhaus.automation.github.EventQueryContext;
import org.commonhaus.automation.github.context.DataDiscussion;
import org.commonhaus.automation.github.context.DataDiscussionCategory;
import org.commonhaus.automation.github.context.EventData;
import org.commonhaus.automation.github.context.EventPayload;
import org.commonhaus.automation.github.context.EventType;

/**
 * Only applies to GitHub Discussions
 */
public class MatchCategory {
    final static List<EventType> eventsWithCategories = List.of(EventType.discussion, EventType.discussion_comment);
    List<String> category;

    public boolean matches(EventQueryContext queryContext) {
        EventData eventData = queryContext.getEventData();
        if (eventData == null || !eventsWithCategories.contains(queryContext.getEventType())) {
            return false;
        }

        EventPayload.DiscussionPayload payload = eventData.getEventPayload();
        DataDiscussion discussion = payload.discussion;
        if (discussion == null) {
            return false;
        }

        DataDiscussionCategory discussionCategory = discussion.category;
        for (String cat : category) {
            if (cat.equals(discussionCategory.id)
                    || cat.equalsIgnoreCase(discussionCategory.name)) {
                return true;
            }
        }

        return false;
    }
}
