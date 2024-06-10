package org.commonhaus.automation.admin.config;

import com.fasterxml.jackson.annotation.JsonAlias;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record AdminConfigFile(
        @JsonAlias("group_management") TeamManagementConfig groupManagement,
        @JsonAlias("user_management") UserManagementConfig userManagement,
        @JsonAlias("email_address") EmailAddress emailAddress) {

    public static final String NAME = LaunchMode.current() == LaunchMode.DEVELOPMENT ? "cf-admin-dev.yml" : "cf-admin.yml";
}
