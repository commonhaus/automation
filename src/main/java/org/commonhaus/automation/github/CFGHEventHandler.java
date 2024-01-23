package org.commonhaus.automation.github;

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

@Target({ PARAMETER, TYPE })
@Retention(RUNTIME)
public @interface CFGHEventHandler {

    String name();

    String description();

    boolean enabledByDefault();

    String[] requiredLabels();

}
