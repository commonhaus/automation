package org.commonhaus.automation.config;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

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

    /**
     * @return the database ID and node ID for the bot user in dry run mode
     */
    DryRunBotConfig dryRunBot();

    /**
     * @return true if discoveryEnabled is unset or is set to true
     */
    default boolean isDiscoveryEnabled() {
        return discoveryEnabled().orElse(true);
    }

    /**
     * @return true if dryRun is set to true, false otherwise (including when unset)
     */
    default boolean isDryRun() {
        return dryRun().orElse(false);
    }

    @ConfigMapping(prefix = "automation.dryRunBot")
    interface DryRunBotConfig {
        @WithDefault("12345")
        int databaseId();

        @WithDefault("D_FAKE_ID")
        String nodeId();

        @WithDefault("https://example.com")
        String url();
    }
}
