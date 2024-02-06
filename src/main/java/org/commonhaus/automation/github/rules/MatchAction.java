package org.commonhaus.automation.github.rules;

import java.util.ArrayList;
import java.util.List;

import org.commonhaus.automation.github.EventData;
import org.commonhaus.automation.github.QueryHelper.QueryContext;

public class MatchAction {

    public List<String> include = new ArrayList<>(1);
    public List<String> exclude = new ArrayList<>(1);

    public MatchAction(List<String> actions) {
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
        if (eventData == null) {
            return false;
        }
        String action = eventData.getAction();
        if (!exclude.isEmpty() && exclude.stream().anyMatch(x -> x.equalsIgnoreCase(action))) {
            return false;
        }
        return include.isEmpty() || include.stream().anyMatch(x -> x.equalsIgnoreCase(action));
    }
}
