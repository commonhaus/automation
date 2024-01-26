package org.commonhaus.automation.github.actions;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(as = EmailAction.class)
public class EmailAction extends Action {
    String address;
    String template;
}
