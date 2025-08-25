package org.commonhaus.automation.hm;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;

import java.io.IOException;

import jakarta.inject.Inject;

import org.commonhaus.automation.github.context.ContextHelper;
import org.commonhaus.automation.github.context.GitHubTeamService;
import org.commonhaus.automation.hm.github.AppContextService;
import org.commonhaus.automation.queue.PeriodicUpdateQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.InjectMock;

public class HausManagerTestBase extends ContextHelper {

    static final DefaultValues PRIMARY = new DefaultValues(
            46053716,
            new Resource(144493209, "test-org"),
            new Resource("test-org/test-repo"));

    static final DefaultValues PROJECT_ORG = new DefaultValues(
            569084570,
            new Resource(123456789, "other-org"),
            new Resource("other-org/primary-repo"));

    static final DefaultValues PROJECT_TWO = new DefaultValues(
            569084570,
            new Resource(123456789, "other-org"),
            new Resource("other-org/project"));

    @Inject
    TestManagerBotConfig configProducer;

    @Inject
    AppContextService appContextService;

    @Inject
    PeriodicUpdateQueue updateQueue;

    @InjectMock
    GitHubTeamService teamService;

    @Inject
    protected ObjectMapper objectMapper;

    @Inject
    TeamConflictResolver conflictResolver;

    MockInstallation project_org;

    @BeforeEach
    void setup() throws IOException {
        reset(); // reset all mocks
        conflictResolver.reset(); // reset conflict resolver

        setupDefaultMocks(PRIMARY);
        project_org = setupInstallationMocks(PROJECT_ORG);
    }

    @AfterEach
    void waitForQueue() {
        // Make sure queue is drained between tests
        await().atMost(5, SECONDS).until(() -> updateQueue.isEmpty());
    }
}
