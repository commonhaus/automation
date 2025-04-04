package org.commonhaus.automation.hr.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.commonhaus.automation.github.context.DataLabel;
import org.commonhaus.automation.github.context.GitHubQueryContext;

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

    public boolean matches(GitHubQueryContext qc, String nodeId) {
        if (nodeId == null) {
            return false;
        }

        Collection<DataLabel> itemLabels = qc.getLabels(nodeId);

        if (!include.isEmpty() && (itemLabels == null || itemLabels.isEmpty())) {
            return false;
        }

        if (!exclude.isEmpty() && itemLabels.stream().anyMatch(x -> exclude.contains(x.name) || exclude.contains(x.id))) {
            return false;
        }
        return include.isEmpty() || itemLabels.stream().anyMatch(x -> include.contains(x.name) || include.contains(x.id));
    }
}
