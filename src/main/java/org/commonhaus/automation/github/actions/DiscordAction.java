package org.commonhaus.automation.github.actions;

import org.commonhaus.automation.github.model.QueryHelper.QueryContext;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(as = DiscordAction.class)
public class DiscordAction extends Action {

    String channel;

    @Override
    public void apply(QueryContext queryContext) {
        // TODO: stuff
    }

}
