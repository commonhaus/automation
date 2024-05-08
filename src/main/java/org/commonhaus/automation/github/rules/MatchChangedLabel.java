package org.commonhaus.automation.github.rules;

import java.util.ArrayList;
import java.util.List;

import jakarta.json.JsonObject;

import org.commonhaus.automation.github.model.DataLabel;
import org.commonhaus.automation.github.model.JsonAttribute;
import org.commonhaus.automation.github.model.QueryHelper.QueryContext;

public class MatchChangedLabel {
    public final List<String> labelAdded = new ArrayList<>(1);
    public final List<String> labelRemoved = new ArrayList<>(1);

    public MatchChangedLabel(List<String> actions) {
        actions.forEach(x -> {
            if (x.startsWith("!")) {
                labelRemoved.add(x.substring(1));
            } else {
                labelAdded.add(x);
            }
        });
    }

    public boolean matches(QueryContext queryContext) {
        JsonObject payload = queryContext.getJsonData();
        DataLabel eventLabel = JsonAttribute.label.labelFrom(payload);
        if (eventLabel == null) {
            return false;
        }

        return switch (queryContext.getActionType()) {
            case labeled -> labelAdded.contains(eventLabel.name) || labelAdded.contains(eventLabel.id);
            case unlabeled -> labelRemoved.contains(eventLabel.name) || labelRemoved.contains(eventLabel.id);
            default -> false;
        };
    }
}
