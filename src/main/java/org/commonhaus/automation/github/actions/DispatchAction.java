package org.commonhaus.automation.github.actions;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(as = DispatchAction.class)
public class DispatchAction extends Action {
    String repo;

    @JsonAlias("name")
    String workflowName;
}
