package org.commonhaus.automation.github.hr.rules;

import java.util.ArrayList;
import java.util.List;

import jakarta.json.JsonObject;

import org.commonhaus.automation.github.context.DataLabel;
import org.commonhaus.automation.github.context.JsonAttribute;
import org.commonhaus.automation.github.context.QueryContext;

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

    public boolean matches(QueryContext qc) {
        JsonObject payload = qc.getJsonData();
        DataLabel eventLabel = JsonAttribute.label.labelFrom(payload);
        if (eventLabel == null) {
            return false;
        }

        return switch (qc.getActionType()) {
            case labeled -> labelAdded.contains(eventLabel.name) || labelAdded.contains(eventLabel.id);
            case unlabeled -> labelRemoved.contains(eventLabel.name) || labelRemoved.contains(eventLabel.id);
            default -> false;
        };
    }
}
