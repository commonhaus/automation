package org.commonhaus.automation.github.hr.rules;

import java.util.List;

import org.commonhaus.automation.github.context.DataDiscussion;
import org.commonhaus.automation.github.context.DataDiscussionCategory;
import org.commonhaus.automation.github.context.EventData;
import org.commonhaus.automation.github.context.EventPayload;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.github.hr.EventQueryContext;

/**
 * Only applies to GitHub Discussions
 */
public class MatchCategory {
    final static List<EventType> eventsWithCategories = List.of(EventType.discussion, EventType.discussion_comment);
    List<String> category;

    public MatchCategory() {
        this.category = List.of();
    }

    public MatchCategory(List<String> category) {
        this.category = category;
    }

    public boolean matches(EventQueryContext qc) {
        EventData eventData = qc.getEventData();
        if (eventData == null || !eventsWithCategories.contains(qc.getEventType())) {
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
