package org.commonhaus.automation.github.watchers;

import static io.quarkiverse.githubapp.testing.GitHubAppTesting.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;

import org.commonhaus.automation.ContextService;
import org.commonhaus.automation.github.context.ContextHelper;
import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdate;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdateType;
import org.commonhaus.automation.queue.PeriodicUpdateQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEvent;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class FileWatcherTest extends ContextHelper {

    @Inject
    ContextService ctx;

    @Inject
    FileWatcher fileWatcher;

    @Inject
    PeriodicUpdateQueue updateQueue;

    final DefaultValues defaultValues = new DefaultValues(
            51110255,
            new Resource(144493209, "O_kgDOCJzKmQ", "test-org"),
            new Resource(728420050, "R_kgDOK2rO0g", "test-org/test-repo"));

    List<FileUpdate> updateRef = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setup() throws IOException {
        reset();
        updateRef.clear();
        fileWatcher.reset();
    }

    @AfterEach
    void cleanup() {
        fileWatcher.dumpWatcherState();
        System.out.println(updateQueue);
        await().atMost(2, TimeUnit.SECONDS).until(() -> updateQueue.isEmpty());
    }

    void recordFileUpdate(FileUpdate update) {
        updateRef.add(update);
    }

    @Test
    void testFileWatcherNotification() throws IOException {
        given()
                .github(mocks -> {
                    MockInstallation myMocks = setupGivenMocks(mocks, defaultValues);

                    // Register file watchers
                    fileWatcher.watchFile("testGroup", myMocks.installationId(),
                            myMocks.repository().getFullName(), "added.md",
                            this::recordFileUpdate);

                    fileWatcher.watchFile("testGroup", myMocks.installationId(),
                            myMocks.repository().getFullName(), "modified.md",
                            this::recordFileUpdate);

                    fileWatcher.watchFile("testGroup", myMocks.installationId(),
                            myMocks.repository().getFullName(), "removed.md",
                            this::recordFileUpdate);

                    fileWatcher.watchFile("testGroup2", myMocks.installationId(),
                            myMocks.repository().getFullName(), "modified.md",
                            this::recordFileUpdate);
                })
                .when()
                .payloadFromClasspath("/github/eventFilePush.json")
                .event(GHEvent.PUSH)
                .then()
                .github(mocks -> {

                });

        await().atMost(5, TimeUnit.SECONDS).until(() -> updateQueue.isEmpty());
        assertThat(updateRef.size()).isEqualTo(4);
        assertThat(updateRef).extracting(FileUpdate::updateType)
                .containsAll(List.of(FileUpdateType.ADDED, FileUpdateType.MODIFIED, FileUpdateType.REMOVED));
    }

    @Test
    void testFileWatcherMultipleCommits() throws Exception {
        given()
                .github(mocks -> {
                    MockInstallation myMocks = setupGivenMocks(mocks, defaultValues);

                    // Register file watchers
                    fileWatcher.watchFile("testGroup", myMocks.installationId(),
                            myMocks.repository().getFullName(), "added.md",
                            this::recordFileUpdate);

                    fileWatcher.watchFile("testGroup", myMocks.installationId(),
                            myMocks.repository().getFullName(), "modified.md",
                            this::recordFileUpdate);

                    fileWatcher.watchFile("testGroup", myMocks.installationId(),
                            myMocks.repository().getFullName(), "removed.md",
                            this::recordFileUpdate);
                })
                .when()
                .payloadFromClasspath("/github/eventFilePush.multipleCommits.json")
                .event(GHEvent.PUSH)
                .then()
                .github(mocks -> {
                });

        await().atMost(3, TimeUnit.SECONDS).until(() -> updateQueue.isEmpty());
        assertThat(updateRef.size()).isEqualTo(3);
        // each file is changed several times.
        // The last time should be the one that is recorded, and it should
        // match the update type
        for (var update : updateRef) {
            if (update.filePath().equals("added.md")) {
                // if both added and modified, modified should be the last one
                assertThat(update.updateType()).isEqualTo(FileUpdateType.MODIFIED);
            } else if (update.filePath().equals("modified.md")) {
                assertThat(update.updateType()).isEqualTo(FileUpdateType.MODIFIED);
            } else if (update.filePath().equals("removed.md")) {
                assertThat(update.updateType()).isEqualTo(FileUpdateType.REMOVED);
            }
        }
    }

    @Test
    void testIgnoreNonMainBranch() throws IOException {
        given()
                .github(mocks -> {
                    MockInstallation myMocks = setupGivenMocks(mocks, defaultValues);

                    // Register file watcher
                    fileWatcher.watchFile("testGroup", myMocks.installationId(),
                            myMocks.repository().getFullName(), "notIncluded.md",
                            this::recordFileUpdate);
                })
                .when()
                .payloadFromClasspath("/github/eventFilePush.altBranch.json")
                .event(GHEvent.PUSH)
                .then()
                .github(mocks -> {
                    // No processing should happen
                });

        // Give some time for any processing that shouldn't happen
        await().atLeast(3, TimeUnit.SECONDS).failFast(() -> updateQueue.isEmpty());
        assertThat(updateRef).isEmpty();
    }

    @Test
    void testWatcherCleanup() throws IOException {
        AtomicInteger callbackCounter = new AtomicInteger();
        MockInstallation myMocks = setupDefaultMocks(defaultValues);

        // Register file watcher
        fileWatcher.watchFile("testGroup", myMocks.installationId(),
                myMocks.repository().getFullName(), "added.md",
                update -> callbackCounter.incrementAndGet());

        assertThat(fileWatcher.isWatching(defaultValues.repoFullName())).isTrue();

        // Trigger repository removal
        triggerRepositoryDiscovery(DiscoveryAction.REMOVED, myMocks, false);

        await().atLeast(3, TimeUnit.SECONDS).failFast(() -> updateQueue.isEmpty());
        assertThat(callbackCounter.get()).isEqualTo(0);

        assertThat(fileWatcher.isWatching(defaultValues.repoFullName())).isFalse();
    }

    @Test
    void testUnwatchAll() throws IOException {
        AtomicInteger callbackCounter = new AtomicInteger();
        MockInstallation myMocks = setupDefaultMocks(defaultValues);

        // Register file watcher
        fileWatcher.watchFile("testGroup", myMocks.installationId(),
                myMocks.repository().getFullName(), "added.md",
                update -> callbackCounter.incrementAndGet());

        fileWatcher.unwatchAll("testGroup");

        await().atLeast(3, TimeUnit.SECONDS).failFast(() -> updateQueue.isEmpty());
        assertThat(fileWatcher.repositoryFiles).isEmpty();
    }

    @Test
    void testUpdateAll() throws IOException {
        MockInstallation myMocks = setupDefaultMocks(defaultValues);

        ScopedQueryContext qc = new ScopedQueryContext(ctx, hausMocks.installationId(), hausMocks.repository())
                .withExisting(hausMocks.github()).withExisting(hausMocks.dql());

        when(ctx.getOrgScopedQueryContext(anyString())).thenReturn(qc);

        // Register file watchers
        fileWatcher.watchFile("testGroup", myMocks.installationId(),
                myMocks.repository().getFullName(), "added.md",
                this::recordFileUpdate);

        fileWatcher.watchFile("testGroup", myMocks.installationId(),
                myMocks.repository().getFullName(), "modified.md",
                this::recordFileUpdate);

        fileWatcher.watchFile("testGroup", myMocks.installationId(),
                myMocks.repository().getFullName(), "removed.md",
                this::recordFileUpdate);

        fileWatcher.watchFile("testGroup2", myMocks.installationId(),
                myMocks.repository().getFullName(), "modified.md",
                this::recordFileUpdate);

        fileWatcher.refresh(ctx, "testGroup");

        await().atMost(5, TimeUnit.SECONDS).until(() -> updateQueue.isEmpty());
        assertThat(updateRef.size()).isEqualTo(3);
        assertThat(updateRef).extracting(FileUpdate::updateType).contains(FileUpdateType.REFRESH);
    }
}
