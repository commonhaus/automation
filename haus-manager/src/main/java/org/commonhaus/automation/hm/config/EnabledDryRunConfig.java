package org.commonhaus.automation.hm.config;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Base configuration for features that support enabled/dryRun flags.
 *
 * @param enabled Whether the feature is enabled (default: true)
 * @param dryRun If true, perform checks but don't make changes (default: false)
 */
@RegisterForReflection
public record EnabledDryRunConfig(Boolean enabled, Boolean dryRun) {
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public boolean isDryRun() {
        return dryRun != null && dryRun;
    }
}
