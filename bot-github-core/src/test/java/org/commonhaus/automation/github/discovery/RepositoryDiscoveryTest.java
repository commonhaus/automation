package org.commonhaus.automation.github.discovery;

import static io.quarkiverse.githubapp.testing.GitHubAppTesting.given;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.commonhaus.automation.github.discovery.EventCounter.collectInstallationEvent;
import static org.commonhaus.automation.github.discovery.EventCounter.collectRepositoryEvent;
import static org.commonhaus.automation.github.discovery.EventCounter.countGHInstallationEvent;
import static org.commonhaus.automation.github.discovery.EventCounter.countGHInstallationRepoEvent;

import jakarta.inject.Inject;

import org.commonhaus.automation.github.context.ContextHelper;
import org.commonhaus.automation.github.context.TestBotConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEvent;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class RepositoryDiscoveryTest extends ContextHelper {

    final DefaultValues defaultValues = new DefaultValues(
            50264360,
            new Resource(801851090, "test-org"),
            new Resource("test-org/test-repo"));

    @Inject
    EventCounter eventCounter;

    @Inject
    TestBotConfig configProducer;

    @Inject
    RepositoryDiscovery repositoryDiscovery;

    @BeforeEach
    void setup() {
        reset();
        configProducer.reset();
        eventCounter.reset();
    }

    @Test
    void testDiscoveryWhenInstallationCreated() throws Exception {
        configProducer.setDiscoveryEnabled(true);

        given()
                .github(mocks -> {
                    System.out.println("GIVEN mocks: " + mocks);
                    setupGivenMocks(mocks, defaultValues);
                    //
                })
                .when().payloadFromClasspath("/github/eventInstallationCreated.json")
                .event(GHEvent.INSTALLATION)
                .then().github(mocks -> {
                    System.out.println("THEN mocks: " + mocks);
                });

        await().atMost(5, SECONDS).until(() -> countGHInstallationEvent.get() > 0);
        await().atMost(5, SECONDS).until(() -> collectInstallationEvent.size() > 0);

        assertThat(countGHInstallationEvent.get()).isEqualTo(1);
        assertThat(collectRepositoryEvent.size()).isEqualTo(3);
        assertThat(collectInstallationEvent.size()).isEqualTo(1);

        assertThat(collectInstallationEvent.get(0).action()).isEqualTo(DiscoveryAction.INSTALL_ADDED);
    }

    @Test
    void testDiscoveryWhenInstallationDeleted() throws Exception {
        given()
                .github(mocks -> {
                    System.out.println("GIVEN mocks: " + mocks);
                    //
                })
                .when().payloadFromClasspath("/github/eventInstallationDeleted.json")
                .event(GHEvent.INSTALLATION)
                .then().github(mocks -> {
                    System.out.println("THEN mocks: " + mocks);
                });
        await().atMost(5, SECONDS).until(() -> countGHInstallationEvent.get() > 0);
        await().atMost(5, SECONDS).until(() -> collectInstallationEvent.size() > 0);

        assertThat(countGHInstallationEvent.get()).isEqualTo(1);
        assertThat(collectRepositoryEvent.size()).isEqualTo(3);
        assertThat(collectInstallationEvent.size()).isEqualTo(1);

        assertThat(collectInstallationEvent.get(0).action()).isEqualTo(DiscoveryAction.INSTALL_REMOVED);
    }

    @Test
    void testDiscoveryWhenInstallationRepositoryAdded() throws Exception {
        // This event occurs when there is activity relating to which repositories a GitHub App
        // installation can access.
        given()
                .github(mocks -> {
                    System.out.println("GIVEN mocks: " + mocks);
                    //
                })
                .when().payloadFromClasspath("/github/eventInstallationRepoAdded.json")
                .event(GHEvent.INSTALLATION_REPOSITORIES)
                .then().github(mocks -> {
                    System.out.println("THEN mocks: " + mocks);
                });

        await().atMost(5, SECONDS).until(() -> countGHInstallationRepoEvent.get() > 0);
        await().atMost(5, SECONDS).until(() -> collectRepositoryEvent.size() > 0);

        assertThat(countGHInstallationRepoEvent.get()).isEqualTo(1);
        assertThat(collectRepositoryEvent.size()).isEqualTo(1);
        assertThat(collectInstallationEvent.size()).isEqualTo(0);

        assertThat(collectRepositoryEvent.get(0).action()).isEqualTo(DiscoveryAction.ADDED);
    }

    @Test
    void testDiscoveryWhenInstallationRepositoryRemoved() throws Exception {
        // This event occurs when there is activity relating to which repositories a GitHub App
        // installation can access.
        given()
                .github(mocks -> {
                    System.out.println("GIVEN mocks: " + mocks);
                    //
                })
                .when().payloadFromClasspath("/github/eventInstallationRepoRemoved.json")
                .event(GHEvent.INSTALLATION_REPOSITORIES)
                .then().github(mocks -> {
                    System.out.println("THEN mocks: " + mocks);
                });

        await().atMost(5, SECONDS).until(() -> countGHInstallationRepoEvent.get() > 0);
        await().atMost(5, SECONDS).until(() -> collectRepositoryEvent.size() > 0);

        assertThat(countGHInstallationRepoEvent.get()).isEqualTo(1);
        assertThat(collectRepositoryEvent.size()).isEqualTo(1);
        assertThat(collectInstallationEvent.size()).isEqualTo(0);

        assertThat(collectRepositoryEvent.get(0).action()).isEqualTo(DiscoveryAction.REMOVED);
    }
}
