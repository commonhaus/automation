package org.commonhaus.automation.github.watchers;

import static io.quarkiverse.githubapp.testing.GitHubAppTesting.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;

import org.commonhaus.automation.github.context.ContextHelper;
import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.github.watchers.MembershipWatcher.MembershipUpdateType;
import org.commonhaus.automation.queue.PeriodicUpdateQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEvent;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class MembershipWatcherTest extends ContextHelper {

    // Team Membership Changes: Test that watchers are notified of team changes
    // Collaborator Changes: Test that repository collaborator changes trigger notifications
    // Cleanup: Test that watchers are properly cleaned up when teams or repositories are removed

    @Inject
    MembershipWatcher membershipWatcher;

    @Inject
    PeriodicUpdateQueue updateQueue;

    final DefaultValues defaultValues = new DefaultValues(
            51110255,
            new Resource(144493209, "O_kgDOCJzKmQ", "test-org"),
            new Resource(941352036, "R_kgDOOBvkZA", "test-org/project-teamA"));

    @BeforeEach
    void setup() throws IOException {
        reset();
        membershipWatcher.reset();
    }

    @AfterEach
    void cleanup() {
        membershipWatcher.dumpWatcherState();
        System.out.println(updateQueue);
        await().atMost(2, TimeUnit.SECONDS).until(() -> updateQueue.isEmpty());
    }

    @Test
    void testAddTeamMembership() throws IOException {
        AtomicInteger updateTeam = new AtomicInteger(0);

        given()
                .github(mocks -> {
                    MockInstallation myMocks = setupGivenMocks(mocks, defaultValues);

                    // Register team watcher (member)
                    membershipWatcher.watchMembers("taskGroup", myMocks.installationId(),
                            MembershipUpdateType.TEAM, "test-org/teamA",
                            (event) -> updateTeam.incrementAndGet());
                })
                .when()
                .payloadFromClasspath("/github/eventMembershipAdded.json")
                .event(GHEvent.MEMBERSHIP)
                .then()
                .github(mocks -> {

                });

        await().atMost(5, TimeUnit.SECONDS).until(() -> updateQueue.isEmpty());
        assertThat(updateTeam.get()).isEqualTo(1);
    }

    @Test
    void testAddCollaborator() throws IOException {
        AtomicInteger updateCollaborators = new AtomicInteger(0);

        given()
                .github(mocks -> {
                    MockInstallation myMocks = setupGivenMocks(mocks, defaultValues);

                    // Register repository watcher (collaborator)
                    membershipWatcher.watchMembers("taskGroup", myMocks.installationId(),
                            MembershipUpdateType.COLLABORATOR, "test-org/project-teamA",
                            (event) -> updateCollaborators.incrementAndGet());
                })
                .when()
                .payloadFromClasspath("/github/eventMemberAdded.json")
                .event(GHEvent.MEMBER)
                .then()
                .github(mocks -> {

                });

        await().atMost(5, TimeUnit.SECONDS).until(() -> updateQueue.isEmpty());
        assertThat(updateCollaborators.get()).isEqualTo(1);
    }

    @Test
    void testWatcherCleanup() throws IOException {
        AtomicInteger callbackCounter = new AtomicInteger();
        MockInstallation myMocks = setupDefaultMocks(defaultValues);

        membershipWatcher.watchMembers("taskGroup", myMocks.installationId(),
                MembershipUpdateType.COLLABORATOR, "test-org/project-teamA",
                (event) -> callbackCounter.incrementAndGet());

        assertThat(membershipWatcher.isWatching("test-org")).isTrue();

        // Trigger repository removal
        triggerRepositoryDiscovery(DiscoveryAction.INSTALL_REMOVED, myMocks, false);

        await().atLeast(3, TimeUnit.SECONDS).failFast(() -> updateQueue.isEmpty());
        assertThat(callbackCounter.get()).isEqualTo(0);

        assertThat(membershipWatcher.isWatching("test-org")).isFalse();
    }

    @Test
    void testUnwatchAll() throws IOException {
        AtomicInteger callbackCounter = new AtomicInteger();
        MockInstallation myMocks = setupDefaultMocks(defaultValues);

        membershipWatcher.watchMembers("taskGroup", myMocks.installationId(),
                MembershipUpdateType.COLLABORATOR, "test-org/project-teamA",
                (event) -> callbackCounter.incrementAndGet());

        membershipWatcher.unwatchAll("taskGroup");

        await().atLeast(3, TimeUnit.SECONDS).failFast(() -> updateQueue.isEmpty());
        assertThat(membershipWatcher.orgWatchers).isEmpty();
    }
}
