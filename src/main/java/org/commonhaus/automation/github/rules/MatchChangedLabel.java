package org.commonhaus.automation.github.rules;

import java.util.ArrayList;
import java.util.List;

import org.commonhaus.automation.github.EventData;
import org.commonhaus.automation.github.model.DataLabel;
import org.commonhaus.automation.github.model.EventQueryContext;
import org.commonhaus.automation.github.model.JsonAttribute;

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

    public boolean matches(EventQueryContext queryContext) {
        EventData event = queryContext.getEventData();
        DataLabel eventLabel = JsonAttribute.label.labelFrom(event.getJsonData());
        if (eventLabel == null) {
            return false;
        }

        return switch (event.getActionType()) {
            case labeled -> labelAdded.contains(eventLabel.name) || labelAdded.contains(eventLabel.id);
            case unlabeled -> labelRemoved.contains(eventLabel.name) || labelRemoved.contains(eventLabel.id);
            default -> false;
        };
    }
}
