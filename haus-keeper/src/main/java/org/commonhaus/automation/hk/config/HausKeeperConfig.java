package org.commonhaus.automation.hk.config;

import org.commonhaus.automation.config.EmailNotification;

import com.fasterxml.jackson.annotation.JsonAlias;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record HausKeeperConfig(
        @JsonAlias("user_management") UserManagementConfig userManagement,
        @JsonAlias("project_aliases") AliasManagementConfig projectAliases,
        @JsonAlias("email_address") EmailNotification emailNotifications) {

    public static final String NAME = "cf-haus-keeper.yml";
    public static final String PATH = ".github/" + NAME;
}
