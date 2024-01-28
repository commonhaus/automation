package org.commonhaus.automation.github.rules;

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

        DataDiscussionCategory discussionCategory = discussion.category;
        for (String cat : category) {
            if (cat.equals(discussionCategory.id)
                    || cat.equalsIgnoreCase(discussionCategory.name)
                    || cat.equals(discussionCategory.id)) {
                return true;
            }
        }

        return false;
    }
}
