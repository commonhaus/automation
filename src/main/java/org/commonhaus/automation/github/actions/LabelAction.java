package org.commonhaus.automation.github.actions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.commonhaus.automation.github.EventData;
import org.commonhaus.automation.github.QueryHelper.QueryContext;
import org.commonhaus.automation.github.model.DataLabel;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import io.quarkus.logging.Log;

@JsonDeserialize(as = LabelAction.class)
public class LabelAction extends Action {

    List<String> labels = new ArrayList<>();

    @Override
    public void apply(QueryContext queryContext) {
        EventData eventData = queryContext.getEventData();
        if (eventData == null) {
            return;
        }

        String nodeId = eventData.getLabelableId();
        Collection<DataLabel> repoLabels = queryContext.getCachedLabels(eventData.getRepositoryId());
        List<DataLabel> newLabels = new ArrayList<>();

        // Find the repository label (with id) for each requested label
        for (String labelName : labels) {
            // Find the matching label in repository labels
            DataLabel label = repoLabels.stream()
                    .filter(l -> l.name.equalsIgnoreCase(labelName) || l.id.equals(labelName))
                    .findFirst().orElse(null);

            if (label == null) {
                Log.errorf("Label [%s] not found in repository [%s/%s]", labelName, eventData.getRepoOwner(),
                        eventData.getRepoName());
            } else {
                newLabels.add(label);
            }
        }

        if (!newLabels.isEmpty()) {
            // Modify labels and update cache
            queryContext.modifyLabels(nodeId, newLabels);
        }
    }
}
