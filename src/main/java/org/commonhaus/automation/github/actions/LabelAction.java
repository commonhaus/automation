package org.commonhaus.automation.github.actions;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(as = LabelAction.class)
public class LabelAction extends Action {
    List<String> labels = new ArrayList<>();
}
