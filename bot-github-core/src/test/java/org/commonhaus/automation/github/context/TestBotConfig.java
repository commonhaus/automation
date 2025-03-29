package org.commonhaus.automation.github.context;

import java.time.Duration;
import java.util.Optional;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import org.commonhaus.automation.config.BotConfig;

import io.quarkus.logging.Log;

@ApplicationScoped
@Alternative
@Priority(1)
public class TestBotConfig implements BotConfig {
    boolean discoveryEnabled;
    boolean dryRun;
    String errorEmail;

    protected TestBotConfig() {
        reset();
    }

    public void reset() {
        discoveryEnabled = false;
        dryRun = false;
        errorEmail = "test-bot-error@example.org";
    }

    @Override
    public Optional<String> replyTo() {
        return Optional.of("no-reply@example.com");
    }

    @Override
    public Optional<Boolean> discoveryEnabled() {
        return Optional.of(discoveryEnabled);
    }

    @Override
    public Optional<Boolean> dryRun() {
        return Optional.of(dryRun);
    }

    @Override
    public Optional<String> errorEmailAddress() {
        return Optional.of("test-bot-error@example.org");
    }

    /**
     * @param discoveryEnabled the discoveryEnabled to set
     */
    public void setDiscoveryEnabled(boolean discoveryEnabled) {
        Log.info("Setting discoveryEnabled to " + discoveryEnabled);
        this.discoveryEnabled = discoveryEnabled;
    }

    /**
     * @param dryRun the dryRun to set
     */
    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    /**
     * @param errorEmail the errorEmail to set
     */
    public void setErrorEmail(String errorEmail) {
        this.errorEmail = errorEmail;
    }

    @Override
    public DryRunBotConfig dryRunBot() {
        return new DryRunBotConfig() {
            @Override
            public int databaseId() {
                return 12345;
            }

            @Override
            public String nodeId() {
                return "MDQ6VXNlcjE=";
            }

            @Override
            public String url() {
                return "https://example.com";
            }
        };
    }

    @Override
    public QueueConfig queue() {
        return new QueueConfig() {
            @Override
            public Duration initialDelay() {
                return Duration.ofMillis(1);
            }

            @Override
            public Duration period() {
                return Duration.ofMillis(1);
            }
        };
    }

    @Override
    public Optional<OpenCollectiveConfig> openCollective() {
        return Optional.empty();
    }
}
