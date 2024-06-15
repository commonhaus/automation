package org.commonhaus.automation.admin.config;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record SponsorsConfig(
        String sponsorable,
        String repository,
        Boolean dryRun) {
    public Boolean dryRun() {
        return dryRun != null && dryRun;
    }
}
