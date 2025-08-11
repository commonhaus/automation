package org.commonhaus.automation.github.context;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import org.commonhaus.automation.config.BotConfig;

import io.quarkus.logging.Log;

@ApplicationScoped
@Alternative
@Priority(1)
public class TestBotConfig implements BotConfig {
    private static final AtomicBoolean DISCOVERY_ENABLED = new AtomicBoolean(false);
    private static final AtomicBoolean DRY_RUN = new AtomicBoolean(false);
    private static final AtomicBoolean OC_CONFIG_ENABLED = new AtomicBoolean(false);
    String errorEmail;

    protected TestBotConfig() {
        reset();
    }

    public void reset() {
        DISCOVERY_ENABLED.set(false);
        DRY_RUN.set(false);
        OC_CONFIG_ENABLED.set(false);
        errorEmail = "test-bot-error@example.org";
    }

    @Override
    public Optional<String> replyTo() {
        return Optional.of("no-reply@example.com");
    }

    @Override
    public Optional<Boolean> discoveryEnabled() {
        return Optional.of(DISCOVERY_ENABLED.get());
    }

    @Override
    public Optional<Boolean> dryRun() {
        return Optional.of(DRY_RUN.get());
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
        DISCOVERY_ENABLED.set(discoveryEnabled);
    }

    /**
     * @param dryRun the dryRun to set
     */
    public void setDryRun(boolean dryRun) {
        DRY_RUN.set(dryRun);
    }

    /**
     * @param dryRun the dryRun to set
     */
    public void ocConfigEnabled(boolean ocConfigEnabled) {
        OC_CONFIG_ENABLED.set(ocConfigEnabled);
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

            @Override
            public Optional<String> stateDirectory() {
                return Optional.empty();
            }

            @Override
            public Optional<String> stateFile() {
                return Optional.empty();
            }
        };
    }

    @Override
    public Optional<OpenCollectiveConfig> openCollective() {
        boolean isOcConfigEnabled = OC_CONFIG_ENABLED.get();
        if (!isOcConfigEnabled) {
            return Optional.empty();
        }
        return Optional.of(new OpenCollectiveConfig() {
            @Override
            public Optional<String> collectiveSlug() {
                return Optional.of("commonhaus-foundation");
            }

            @Override
            public Optional<String> personalToken() {
                return Optional.empty();
            }

            @Override
            public String apiEndpoint() {
                return OpenCollectiveConfig.GRAPHQL_ENDPOINT;
            }
        });
    }
}
