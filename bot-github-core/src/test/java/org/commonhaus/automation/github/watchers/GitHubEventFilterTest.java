package org.commonhaus.automation.github.watchers;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import org.commonhaus.automation.config.BotConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.githubapp.GitHubEvent;
import io.vertx.core.json.JsonObject;

class GitHubEventFilterTest {

    GitHubEventFilter filter;

    @BeforeEach
    void setup() {
        filter = new GitHubEventFilter();
    }

    // -- helpers ---------------------------------------------------------------

    /**
     * Returns a BotConfig whose stateDirectory points at the given path string
     * (or is empty when {@code null}).
     */
    BotConfig configWithDir(String dir) {
        return new BotConfig() {
            @Override
            public Optional<String> replyTo() {
                return Optional.empty();
            }

            @Override
            public Optional<Boolean> discoveryEnabled() {
                return Optional.empty();
            }

            @Override
            public Optional<Boolean> dryRun() {
                return Optional.empty();
            }

            @Override
            public Optional<String> errorEmailAddress() {
                return Optional.empty();
            }

            @Override
            public Optional<OpenCollectiveConfig> openCollective() {
                return Optional.empty();
            }

            @Override
            public DryRunBotConfig dryRunBot() {
                return new DryRunBotConfig() {
                    @Override
                    public int databaseId() {
                        return 0;
                    }

                    @Override
                    public String nodeId() {
                        return "";
                    }

                    @Override
                    public String url() {
                        return "";
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
                        return Optional.ofNullable(dir);
                    }

                    @Override
                    public Optional<String> stateFile() {
                        return Optional.empty();
                    }
                };
            }

            @Override
            public ScopeNotificationConfig scopeNotification() {
                return () -> Optional.empty();
            }
        };
    }

    /** Minimal GitHubEvent that only provides an installation ID. */
    GitHubEvent eventWithInstallation(Long installationId) {
        return new GitHubEvent() {
            @Override
            public Long getInstallationId() {
                return installationId;
            }

            @Override
            public boolean supportsInstallation() {
                return installationId != null;
            }

            @Override
            public Optional<String> getAppName() {
                return Optional.empty();
            }

            @Override
            public String getDeliveryId() {
                return "test-delivery";
            }

            @Override
            public Optional<String> getRepository() {
                return Optional.empty();
            }

            @Override
            public String getRepositoryOrThrow() {
                throw new UnsupportedOperationException();
            }

            @Override
            public String getEvent() {
                return "test";
            }

            @Override
            public String getAction() {
                return "test";
            }

            @Override
            public String getEventAction() {
                return "test.test";
            }

            @Override
            public String getPayload() {
                return "{}";
            }

            @Override
            public JsonObject getParsedPayload() {
                return new JsonObject();
            }

            @Override
            public boolean isReplayed() {
                return false;
            }
        };
    }

    /** Path to the test-resources directory where ignored-installations.yaml lives. */
    static String testResourcesDir() {
        URL url = GitHubEventFilterTest.class.getClassLoader().getResource("ignored-installations.yaml");
        if (url == null) {
            throw new IllegalStateException("ignored-installations.yaml not found on test classpath");
        }
        return Path.of(url.getPath()).getParent().toString();
    }

    // -- tests ----------------------------------------------------------------

    @Test
    void blockedIdReturnsTrueFromIsBlockedLong() {
        filter.botConfig = configWithDir(testResourcesDir());
        filter.init(null);

        assertThat(filter.isBlocked(111111111L)).isTrue();
        assertThat(filter.isBlocked(222222222L)).isTrue();
    }

    @Test
    void nonBlockedIdReturnsFalseFromIsBlockedLong() {
        filter.botConfig = configWithDir(testResourcesDir());
        filter.init(null);

        assertThat(filter.isBlocked(999999999L)).isFalse();
    }

    @Test
    void isBlockedEventReturnsTrueWhenInstallationIdMatches() {
        filter.botConfig = configWithDir(testResourcesDir());
        filter.init(null);

        assertThat(filter.isBlocked(eventWithInstallation(111111111L))).isTrue();
    }

    @Test
    void isBlockedEventReturnsFalseWhenInstallationIdDoesNotMatch() {
        filter.botConfig = configWithDir(testResourcesDir());
        filter.init(null);

        assertThat(filter.isBlocked(eventWithInstallation(999999999L))).isFalse();
    }

    @Test
    void isBlockedEventReturnsFalseWhenInstallationIdIsNull() {
        filter.botConfig = configWithDir(testResourcesDir());
        filter.init(null);

        assertThat(filter.isBlocked(eventWithInstallation(null))).isFalse();
    }

    @Test
    void absentFileInitializesWithEmptyBlocklist() {
        filter.botConfig = configWithDir("/nonexistent/path/that/does/not/exist");
        filter.init(null);

        assertThat(filter.isBlocked(111111111L)).isFalse();
        assertThat(filter.isBlocked(eventWithInstallation(111111111L))).isFalse();
    }

    @Test
    void noStateDirectoryInitializesWithEmptyBlocklist() {
        filter.botConfig = configWithDir(null);
        filter.init(null);

        assertThat(filter.isBlocked(111111111L)).isFalse();
    }

    @Test
    void malformedFileInitializesWithEmptyBlocklist() throws Exception {
        // Write a temp file with content that can't parse as Map<Long,String>
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("gef-test");
        java.nio.file.Path badFile = tempDir.resolve("ignored-installations.yaml");
        java.nio.file.Files.writeString(badFile, "not: valid: yaml: [structure: for: map\n");

        filter.botConfig = configWithDir(tempDir.toString());
        filter.init(null); // must not throw

        assertThat(filter.isBlocked(111111111L)).isFalse();
        assertThat(filter.isBlocked(eventWithInstallation(111111111L))).isFalse();
    }
}
