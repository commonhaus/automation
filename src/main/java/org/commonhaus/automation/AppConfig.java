package org.commonhaus.automation;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;

/**
 * Mapping for GitHub App and Discord Bot configuration
 */
@ConfigMapping(prefix = "automation")
public interface AppConfig {

    Optional<String> replyTo();

    Optional<String> cronExpr();

    Optional<Boolean> discoveryEnabled();

    default boolean isDiscoveryEnabled() {
        Optional<Boolean> discoveryEnabled = discoveryEnabled();
        return discoveryEnabled.isEmpty() || discoveryEnabled.get();
    }

    Optional<Boolean> dryRun();

    default boolean isDryRun() {
        Optional<Boolean> dryRun = dryRun();
        return dryRun.isPresent() && dryRun.get();
    }
}
