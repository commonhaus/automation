package org.commonhaus.automation.admin.config;

import com.fasterxml.jackson.annotation.JsonAlias;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record AdminConfigFile(
        @JsonAlias("group_management") TeamManagementConfig groupManagement,
        @JsonAlias("user_management") UserManagementConfig userManagement,
        @JsonAlias("email_address") EmailAddress emailAddress) {

    public static final String NAME = "cf-admin.yml";
}
