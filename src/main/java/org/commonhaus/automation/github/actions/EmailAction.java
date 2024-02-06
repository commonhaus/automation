package org.commonhaus.automation.github.actions;

import org.commonhaus.automation.github.QueryHelper.QueryContext;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(as = EmailAction.class)
public class EmailAction extends Action {

    String address;
    String template;

    @Override
    public void apply(QueryContext queryContext) {
        // TODO: stuff
    }
}
