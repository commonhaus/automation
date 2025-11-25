package org.commonhaus.automation.hm.github;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

import java.io.IOException;

import jakarta.inject.Inject;

import org.commonhaus.automation.github.context.ContextHelper;
import org.commonhaus.automation.github.context.GitHubTeamService;
import org.commonhaus.automation.hm.TeamConflictResolver;
import org.commonhaus.automation.queue.PeriodicUpdateQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.githubapp.TokenGitHubClients;
import io.quarkus.test.InjectMock;

public class HausManagerTestBase extends ContextHelper {

    public static final DefaultValues PRIMARY = new DefaultValues(
            46053716,
            new Resource(144493209, "test-org"),
            new Resource("test-org/test-repo"));

    public static final DefaultValues HOME_PROJECT_1 = new DefaultValues(
            46053716,
            new Resource(123456789, "test-org"),
            new Resource("test-org/project-one"));

    public static final DefaultValues HOME_PROJECT_2 = new DefaultValues(
            46053716,
            new Resource(123456789, "test-org"),
            new Resource("test-org/project-two"));

    public static final DefaultValues PROJECT_ORG = new DefaultValues(
            569084570,
            new Resource(123456789, "other-org"),
            new Resource("other-org/primary-repo"));

    public static final DefaultValues PROJECT_TWO = new DefaultValues(
            569084570,
            new Resource(123456789, "other-org"),
            new Resource("other-org/project"));

    @Inject
    protected TestManagerBotConfig configProducer;

    @Inject
    protected AppContextService ctx;

    @Inject
    protected PeriodicUpdateQueue updateQueue;

    @InjectMock
    protected GitHubTeamService teamService;

    @Inject
    protected ObjectMapper objectMapper;

    @Inject
    protected TeamConflictResolver conflictResolver;

    protected MockInstallation project_org;
    protected TokenGitHubClients mockTokenClients = Mockito.mock(TokenGitHubClients.class);

    @BeforeEach
    protected void setup() throws IOException {
        reset(); // reset all mocks
        conflictResolver.reset(); // reset conflict resolver

        setupDefaultMocks(PRIMARY);
        project_org = setupInstallationMocks(PROJECT_ORG);

        ctx.tokenClients = mockTokenClients;
        when(mockTokenClients.getRestClient()).thenReturn(hausMocks.github());
        when(mockTokenClients.getGraphQLClient()).thenReturn(hausMocks.dql());
    }

    @AfterEach
    protected void waitForQueue() {
        // Make sure queue is drained between tests
        await().atMost(5, SECONDS).until(() -> updateQueue.isEmpty());
    }
}
