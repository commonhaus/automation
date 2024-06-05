package org.commonhaus.automation.admin.config;

import com.fasterxml.jackson.annotation.JsonAlias;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record EmailAddress(
        String[] errors,
        @JsonAlias("dry_run") String[] dryRun) {
}
