package org.commonhaus.automation.admin.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record RepositoryConfigFile(
        @JsonProperty("group_management") GroupManagement groupManagement,
        @JsonProperty("email_address") EmailAddress emailAddress) {

    public static final String NAME = "cf-admin.yml";

    public record EmailAddress(
            String[] errors,
            @JsonProperty("dry_run") String[] dryRun) {
    }

    public static class RepositoryConfig {
        protected Boolean enabled;

        protected RepositoryConfig() {
        }

        public boolean isDisabled() {
            return enabled != null && !enabled;
        }
    }
}
