package org.commonhaus.automation.github.rules;

import java.util.List;

import org.commonhaus.automation.github.EventData;
import org.commonhaus.automation.github.QueryHelper.QueryContext;

public class MatchLabel {
    List<String> labels;

    public boolean matches(QueryContext queryContext) {
        EventData eventData = queryContext.getEventData();
        if (eventData == null) {
            return false;
        }

        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'matches'");
    }
}
