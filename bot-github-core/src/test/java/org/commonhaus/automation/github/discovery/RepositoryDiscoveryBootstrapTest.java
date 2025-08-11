package org.commonhaus.automation.github.discovery;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.commonhaus.automation.github.discovery.EventCounter.collectBootstrapEvent;
import static org.commonhaus.automation.github.discovery.EventCounter.collectInstallationEvent;
import static org.commonhaus.automation.github.discovery.EventCounter.collectRepositoryEvent;
import static org.commonhaus.automation.github.discovery.EventCounter.countGHInstallationEvent;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import org.commonhaus.automation.github.context.ContextHelper;
import org.commonhaus.automation.github.context.TestBotConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHApp;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHAuthenticatedAppInstallation;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterable;
import org.kohsuke.github.PagedSearchIterable;

import io.quarkiverse.githubapp.runtime.github.GitHubService;
import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.arc.Arc;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

@QuarkusTest
@GitHubAppTest
public class RepositoryDiscoveryBootstrapTest extends ContextHelper {

    final DefaultValues defaultValues = new DefaultValues(
            50264360,
            new Resource(801851090, "test-org"),
            new Resource("test-org/test-repo"));

    @Inject
    TestBotConfig configProducer;

    @Inject
    EventCounter eventCounter;

    @Inject
    Event<StartupEvent> fireStartupEvent;

    @Inject
    RepositoryDiscovery repositoryDiscovery;

    @Inject
    GitHubService gitHubService;

    MockInstallation mockInstallation;

    @BeforeEach
    void setup() throws IOException {
        gitHubService = Arc.container().instance(GitHubService.class).get();
        eventCounter.reset();
        mockInstallation = setupCommonMocks(defaultValues);
    }

    @Test
    void testStartupEvent() throws Exception {
        // Discovery is elaborate. It requires a GitHub App installation to be created
        // and repositories to be added to the installation. This test is a sanity check
        // to ensure that the startup event is fired and that the discovery process is
        // triggered.
        long ghiId = mockInstallation.installationId();

        GitHub ac = mocks.applicationClient();
        GitHub github = mockInstallation.github();
        GHApp mockGhApp = mocks.ghObject(GHApp.class, ghiId);
        GHAppInstallation ghAppInstallation = mocks.ghObject(GHAppInstallation.class, mockInstallation.installationId());
        GHAuthenticatedAppInstallation ghai = mock(GHAuthenticatedAppInstallation.class);
        DynamicGraphQLClient graphQLClient = mockInstallation.dql();
        PagedIterable<GHAppInstallation> appInstallationIterable = mockPagedIterable(ghAppInstallation);
        PagedSearchIterable<GHRepository> repositoryIterable = mockPagedSearchIterable(mockInstallation.repository());

        when(gitHubService.getApplicationClient()).thenReturn(ac);
        when(ac.getApp()).thenReturn(mockGhApp);

        when(mockGhApp.listInstallations()).thenReturn(appInstallationIterable);

        when(ghAppInstallation.getId()).thenReturn(mockInstallation.installationId());
        when(gitHubService.getInstallationClient(ghiId)).thenReturn(github);
        when(github.getInstallation()).thenReturn(ghai);
        when(gitHubService.getInstallationGraphQLClient(ghiId)).thenReturn(graphQLClient);

        when(ghai.listRepositories()).thenReturn(repositoryIterable);

        // repost startup event to trigger discovery
        configProducer.setDiscoveryEnabled(true);
        repositoryDiscovery.discoverRepositories();

        await().atMost(5, SECONDS).until(() -> collectBootstrapEvent.size() > 0);

        assertThat(countGHInstallationEvent.get()).isEqualTo(0);
        assertThat(countGHInstallationEvent.get()).isEqualTo(0);

        assertThat(collectBootstrapEvent.size()).isEqualTo(1);
        assertThat(collectInstallationEvent.size()).isEqualTo(1);
        assertThat(collectRepositoryEvent.size()).isEqualTo(1);
    }
}
