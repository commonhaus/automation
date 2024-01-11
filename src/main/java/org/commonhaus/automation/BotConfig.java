package org.commonhaus.automation;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;

/**
 * Mapping for GitHub App and Discord Bot configuration
 */
@ConfigMapping(prefix = "commonhaus-bot")
public interface BotConfig {

    Optional<Boolean> dryRun();

    public default boolean isDryRun() {
        Optional<Boolean> dryRun = dryRun();
        return dryRun.isPresent() && dryRun.get();
    }
}
