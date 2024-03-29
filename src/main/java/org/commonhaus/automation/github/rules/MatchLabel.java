package org.commonhaus.automation.github.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.commonhaus.automation.github.model.DataLabel;
import org.commonhaus.automation.github.model.QueryHelper.QueryContext;

public class MatchLabel {

    public final List<String> include = new ArrayList<>(1);
    public final List<String> exclude = new ArrayList<>(1);

    public MatchLabel(List<String> actions) {
        actions.forEach(x -> {
            if (x.startsWith("!")) {
                exclude.add(x.substring(1));
            } else {
                include.add(x);
            }
        });
    }

    public boolean matches(QueryContext queryContext, String nodeId) {
        return matches(queryContext, List.of(), nodeId);
    }

    public boolean matches(QueryContext queryContext, List<DataLabel> eventLabels, String nodeId) {
        if (nodeId == null) {
            return false;
        }

        Collection<DataLabel> itemLabels = queryContext.getLabels(nodeId);
        itemLabels.addAll(eventLabels);

        if (!include.isEmpty() && (itemLabels == null || itemLabels.isEmpty())) {
            return false;
        }

        if (!exclude.isEmpty() && itemLabels.stream().anyMatch(x -> exclude.contains(x.name) || exclude.contains(x.id))) {
            return false;
        }
        return include.isEmpty() || itemLabels.stream().anyMatch(x -> include.contains(x.name) || include.contains(x.id));
    }
}
