package org.commonhaus.automation.github.watchers;

import org.commonhaus.automation.github.context.ContextHelper;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class MembershipWatcherTest extends ContextHelper {

    // // Team Membership Changes: Test that watchers are notified of team changes
    // // Collaborator Changes: Test that repository collaborator changes trigger notifications
    // // Cleanup: Test that watchers are properly cleaned up when teams or repositories are removed

    // @Test
    // void testTeamMembershipNotification() throws IOException {
    //     // Set up test repository, organization and team
    //     MockInstallation mockInstall = setupCommonMocks(defaultValues);
    //     String teamFullName = "test-org/test-team";

    //     // Create a callback counter
    //     AtomicInteger callbackCounter = new AtomicInteger(0);
    //     AtomicReference<MembershipUpdateType> updateTypeRef = new AtomicReference<>();

    //     // Register membership watcher
    //     membershipWatcher.watchMembers("testGroup", mockInstall.installationId(),
    //             MembershipUpdateType.TEAM, teamFullName,
    //             update -> {
    //                 callbackCounter.incrementAndGet();
    //                 updateTypeRef.set(update.type());
    //             });

    //     // Create a team event
    //     TeamEvent teamEvent = createMockTeamEvent(mockInstall, ActionType.added);

    //     // Trigger the event
    //     membershipWatcher.handleTeamEvent(teamEvent);

    //     // Wait and verify
    //     await().atMost(5, SECONDS).until(() -> callbackCounter.get() > 0);
    //     assertThat(callbackCounter.get()).isEqualTo(1);
    //     assertThat(updateTypeRef.get()).isEqualTo(MembershipUpdateType.TEAM);
    // }
}
