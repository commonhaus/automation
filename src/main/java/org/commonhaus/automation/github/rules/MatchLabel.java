package org.commonhaus.automation.github.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.commonhaus.automation.github.EventData;
import org.commonhaus.automation.github.model.DataLabel;
import org.commonhaus.automation.github.model.QueryHelper.QueryContext;

public class MatchLabel {

    public List<String> include = new ArrayList<>(1);
    public List<String> exclude = new ArrayList<>(1);

    public MatchLabel(List<String> actions) {
        actions.forEach(x -> {
            if (x.startsWith("!")) {
                exclude.add(x.substring(1));
            } else {
                include.add(x);
            }
        });
    }

    public boolean matches(QueryContext queryContext) {
        EventData eventData = queryContext.getEventData();
        String id = eventData.getNodeId();
        if (id == null) {
            return false;
        }

        Collection<DataLabel> eventLabels = queryContext.getLabels(id);
        if (!include.isEmpty() && (eventLabels == null || eventLabels.isEmpty())) {
            return false;
        }

        if (!exclude.isEmpty() && eventLabels.stream().anyMatch(x -> exclude.contains(x.name) || exclude.contains(x.id))) {
            return false;
        }
        return include.isEmpty() || eventLabels.stream().anyMatch(x -> include.contains(x.name) || include.contains(x.id));
    }
}
