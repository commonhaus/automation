package org.commonhaus.automation.github.rules;

import java.util.Collection;
import java.util.List;

import org.commonhaus.automation.github.EventData;
import org.commonhaus.automation.github.QueryHelper.QueryContext;
import org.commonhaus.automation.github.model.DataLabel;

public class MatchLabel {
    List<String> labels;

    public boolean matches(QueryContext queryContext) {
        EventData eventData = queryContext.getEventData();
        String id = eventData.getLabelableId();
        if (id == null) {
            return false;
        }

        Collection<DataLabel> eventLabels = queryContext.getCachedLabels(id);
        if (eventLabels == null || eventLabels.isEmpty()) {
            return false;
        }

        return false;
    }
}
