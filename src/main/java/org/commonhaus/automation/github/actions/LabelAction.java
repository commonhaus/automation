package org.commonhaus.automation.github.actions;

import java.util.ArrayList;
import java.util.List;

import org.commonhaus.automation.github.EventData;
import org.commonhaus.automation.github.model.EventQueryContext;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(as = LabelAction.class)
public class LabelAction extends Action {

    List<String> labels = new ArrayList<>();

    @Override
    public void apply(EventQueryContext queryContext) {
        EventData eventData = queryContext.getEventData();
        if (eventData == null || labels.isEmpty()) {
            return;
        }
        queryContext.addLabels(eventData, labels);
    }
}
