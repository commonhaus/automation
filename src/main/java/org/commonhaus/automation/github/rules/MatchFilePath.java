package org.commonhaus.automation.github.rules;

import java.util.List;

import org.commonhaus.automation.github.EventData;
import org.commonhaus.automation.github.QueryHelper.QueryContext;
import org.commonhaus.automation.github.model.EventType;

/**
 * Only applies to GitHub Pull Requests
 */
public class MatchFilePath {
    List<String> paths;

    public boolean matches(QueryContext queryContext) {
        EventData eventData = queryContext.getEventData();
        if (eventData == null || eventData.getEventType() != EventType.pull_request) {
            return false;
        }

        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'matches'");
    }
}
