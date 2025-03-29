package org.commonhaus.automation.config;

import java.time.Duration;
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
     * Optional OpenCollective API configuration
     * used to query information on contributors from OpenCollective.
     */
    Optional<OpenCollectiveConfig> openCollective();

    /**
     * Dry run bot configuration.
     *
     * @return {@link DryRunBotConfig}
     */
    DryRunBotConfig dryRunBot();

    /**
     * Configuration for the queue that processes updates.
     *
     * @return {@link QueueConfig}
     */
    QueueConfig queue();

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

    default String display() {
        return """
                dryRun=%s
                discoveryEnabled=%s

                errorEmailAddress=%s
                replyTo=%s

                queue.initialDelay=%s
                queue.period=%s
                """.formatted(
                isDryRun(),
                isDiscoveryEnabled(),
                errorEmailAddress().orElse("N/A"),
                replyTo().orElse("N/A"),
                queue().initialDelay(),
                queue().period());
    }

    interface DryRunBotConfig {
        @WithDefault("12345")
        int databaseId();

        @WithDefault("D_FAKE_ID")
        String nodeId();

        @WithDefault("https://example.com")
        String url();
    }

    interface QueueConfig {
        /**
         * Initial delay before the queue
         */
        @WithDefault("10s")
        Duration initialDelay();

        /**
         * The period between successive executions
         */
        @WithDefault("2s")
        Duration period();
    }

    public interface OpenCollectiveConfig {
        public static final String GRAPHQL_ENDPOINT = "https://api.opencollective.com/graphql/v2";

        @WithDefault(GRAPHQL_ENDPOINT)
        String apiEndpoint();

        /** If not present, request will be rate-limited and read-only */
        Optional<String> personalToken();

        Optional<String> collectiveSlug();
    }
}
