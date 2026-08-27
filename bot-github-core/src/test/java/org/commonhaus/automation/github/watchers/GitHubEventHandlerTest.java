package org.commonhaus.automation.github.watchers;

import static io.quarkiverse.githubapp.testing.GitHubAppTesting.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.inject.Inject;

import org.commonhaus.automation.github.context.ContextHelper;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEvent;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
class GitHubEventHandlerTest extends ContextHelper {

    final DefaultValues defaultValues = new DefaultValues(
            51110255,
            new Resource(144493209, "O_kgDOCJzKmQ", "test-org"),
            new Resource(728420050, "R_kgDOK2rO0g", "test-org/test-repo"));

    @Inject
    FileWatcher fileWatcher;

    @Inject
    GitHubEventFilter gitHubEventFilter;

    final CopyOnWriteArrayList<FileUpdate> updateRef = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setup() throws IOException {
        reset();
        updateRef.clear();
        fileWatcher.reset();
    }

    @AfterEach
    void cleanup() {
        gitHubEventFilter.setBlocklistForTesting(Set.of());
    }

    @Test
    void blockedInstallationPushIsIgnored() throws Exception {
        // installation ID in eventFilePush.json is 51110255
        gitHubEventFilter.setBlocklistForTesting(Set.of(51110255L));

        given()
                .github(mocks -> {
                    MockInstallation myMocks = setupGivenMocks(mocks, defaultValues);
                    fileWatcher.watchFile("testGroup", myMocks.installationId(),
                            myMocks.repository().getFullName(), "added.md",
                            updateRef::add);
                })
                .when()
                .payloadFromClasspath("/github/eventFilePush.json")
                .event(GHEvent.PUSH)
                .then()
                .github(mocks -> {
                });

        // Drain the queue (as other watcher tests do) then confirm the guard fired
        drainQueue(updateQueue, 3);
        assertThat(updateRef).isEmpty();
    }
}
