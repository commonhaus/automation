package org.commonhaus.automation.github.actions;

import java.util.ArrayList;
import java.util.List;

import org.commonhaus.automation.github.EventQueryContext;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(as = LabelAction.class)
public class LabelAction extends Action {

    public final List<String> add = new ArrayList<>(1);
    public final List<String> remove = new ArrayList<>(1);
    private final boolean inactive;

    public LabelAction(List<String> actions) {
        actions.forEach(x -> {
            if (x.startsWith("!")) {
                remove.add(x.substring(1));
            } else {
                add.add(x);
            }
        });
        inactive = add.isEmpty() && remove.isEmpty();
    }

    @Override
    public void apply(EventQueryContext qc) {
        if (inactive) {
            return;
        }
        if (!add.isEmpty()) {
            qc.addLabels(add);
        }
        if (!remove.isEmpty()) {
            qc.removeLabels(remove);
        }
    }
}
