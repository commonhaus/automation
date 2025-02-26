package org.commonhaus.automation.config;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;

/**
 * Configuration for the bot.
 *
 * CDI bean injected by MicroProfile Config.
 */
@ConfigMapping(prefix = "automation")
public interface BotConfig {
    /**
     * Email address used as the reply-to when sending notifications.
     */
    Optional<String> replyTo();

    /**
     * Controls whether repository discovery is enabled.
     * Default is true if not specified.
     */
    Optional<Boolean> discoveryEnabled();

    /**
     * True if changes should not be made to the repository
     */
    Optional<Boolean> dryRun();

    /**
     * Email address to send error logs to.
     */
    Optional<String> errorEmailAddress();

    default boolean isDiscoveryEnabled() {
        Optional<Boolean> discoveryEnabled = discoveryEnabled();
        return discoveryEnabled.isEmpty() || discoveryEnabled.get();
    }

    default boolean isDryRun() {
        Optional<Boolean> dryRun = dryRun();
        return dryRun.isPresent() && dryRun.get();
    }
}
