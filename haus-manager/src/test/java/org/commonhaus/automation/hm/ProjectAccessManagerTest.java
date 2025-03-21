package org.commonhaus.automation.hm;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Set;

import jakarta.inject.Inject;

import org.commonhaus.automation.github.context.ActionType;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdate;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdateType;
import org.commonhaus.automation.github.watchers.MembershipWatcher.MembershipUpdate;
import org.commonhaus.automation.github.watchers.MembershipWatcher.MembershipUpdateType;
import org.commonhaus.automation.github.watchers.MembershipWatcher.RepositoryEvent;
import org.commonhaus.automation.hm.config.OrganizationConfig;
import org.commonhaus.automation.hm.config.ProjectConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class ProjectAccessManagerTest extends HausManagerTestBase {
    final static String taskGroup = ProjectAccessManager.ME + "-" + PRIMARY.repoFullName();

    @Inject
    ProjectAccessManager projectAccessManager;

    Set<String> otherTeamLogins = Set.of("user1", "user2", "other3", "other4");

    @BeforeEach
    @Override
    void setup() throws IOException {
        super.setup();
        Log.info("START: ProjectAccessManagerTest.setup()");

        // Mock the file content for organization config in primary repo
        mockFileContent(hausMocks, ProjectConfig.PATH,
                "src/test/resources/cf-haus-manager.yml");

        // trigger discovery to register installation
        triggerRepositoryDiscovery(DiscoveryAction.ADDED, project_org, true);
    }

    @AfterEach
    void clear() {
        // Reset any internal state if the class has a reset method
        projectAccessManager.reset();
    }

    @Test
    void testRepositoryEvents() throws IOException {
        // Trigger discovery to initialize manager
        triggerRepositoryDiscovery(DiscoveryAction.ADDED, hausMocks, true);

        waitForQueue();

        // Trigger discovery to remove configuration
        triggerRepositoryDiscovery(DiscoveryAction.REMOVED, hausMocks, true);

        // This should be called only once (first event); second event cleans state
        verify(teamService, times(1)).syncCollaborators(any(),
                eq(hausMocks.repository()), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void testConfigurationUpdated() throws IOException {

        mockTeam("other-org/teamA", project_org.github(), otherTeamLogins);

        when(teamService.getTeamLogins(any(), any()))
                .thenReturn(otherTeamLogins);

        projectAccessManager.processFileUpdate(taskGroup, new FileUpdate(
                OrganizationConfig.PATH, FileUpdateType.MODIFIED,
                hausMocks.installationId(), hausMocks.repository(), hausMocks.github()));

        waitForQueue();

        // A change to watched memebership should trigger a sync
        projectAccessManager.processMembershipUpdate(taskGroup,
                new MembershipUpdate(MembershipUpdateType.COLLABORATOR, PRIMARY.orgName(),
                        new RepositoryEvent(
                                hausMocks.github(),
                                hausMocks.installationId(),
                                hausMocks.organization(),
                                hausMocks.repository(),
                                mockUser("test-user"),
                                ActionType.added,
                                EventType.member)));

        waitForQueue();

        // We should expect members of the team, and login from the config file
        Set<String> expectedLogins = Set.of("user1", "user2", "other3", "other4", "user4");
        String expectedRoleString = "push";

        // This should be called twice (file and membership events)
        verify(teamService, times(2)).syncCollaborators(any(),
                eq(hausMocks.repository()),
                argThat(actualRole -> actualRole.toString().equals(expectedRoleString)),
                eq(expectedLogins), any(), anyBoolean(), any());
    }

    @Test
    void testMembershipUpdated() throws IOException {
        // A change to watched memebership should not trigger a sync if the
        // state has been cleared (e.g. after config removed)
        projectAccessManager.processMembershipUpdate(taskGroup,
                new MembershipUpdate(MembershipUpdateType.COLLABORATOR, PRIMARY.orgName(),
                        new RepositoryEvent(
                                hausMocks.github(),
                                hausMocks.installationId(),
                                hausMocks.organization(),
                                hausMocks.repository(),
                                mockUser("test-user"),
                                ActionType.added,
                                EventType.member)));

        // This shouldn't be called. The state is gone.
        verify(teamService, times(0)).syncCollaborators(any(),
                eq(hausMocks.repository()), any(), any(), any(), anyBoolean(), any());
    }
}
