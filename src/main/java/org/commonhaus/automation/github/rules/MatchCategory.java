package org.commonhaus.automation.github.rules;

import java.util.List;

import org.commonhaus.automation.github.EventData;
import org.commonhaus.automation.github.QueryHelper.QueryContext;
import org.kohsuke.github.GHEvent;

public class MatchCategory {
    final static List<GHEvent> eventsWithCategories = List.of(GHEvent.DISCUSSION, GHEvent.DISCUSSION_COMMENT);
    List<String> category;

    public boolean matches(QueryContext queryContext) {
        EventData eventData = queryContext.getEventData();
        if (eventData == null || !eventsWithCategories.contains(eventData.eventType)) {
            return false;
        }


        return false;
    }
}
