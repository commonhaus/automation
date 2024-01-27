package org.commonhaus.automation.github.rules;

import java.util.List;

import org.commonhaus.automation.github.EventData;
import org.commonhaus.automation.github.QueryHelper.QueryContext;

public class MatchAction {

    public List<String> actions;

    public boolean matches(QueryContext queryContext) {
        EventData eventData = queryContext.getEventData();
        if (eventData == null) {
            return false;
        }
        return actions.stream().anyMatch(x -> x.equalsIgnoreCase(eventData.getAction()));
    }
}
