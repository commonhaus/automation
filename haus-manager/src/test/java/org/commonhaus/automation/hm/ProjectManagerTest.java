package org.commonhaus.automation.hm;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
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
import org.commonhaus.automation.hm.config.LatestOrgConfig;
import org.commonhaus.automation.hm.config.OrganizationConfig;
import org.commonhaus.automation.hm.config.ProjectConfig;
import org.commonhaus.automation.hm.github.HausManagerTestBase;
import org.commonhaus.automation.queue.TaskStateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHOrganization.Permission;
import org.kohsuke.github.GHOrganization.RepositoryRole;
import org.kohsuke.github.GHRepository;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.logging.Log;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class ProjectManagerTest extends HausManagerTestBase {
    final static String taskGroup = ProjectManager.repoNametoTaskGroup(HOME_PROJECT_1.repoFullName());

    @Inject
    ProjectManager projectManager;

    @InjectMock
    TaskStateService taskState;

    @InjectMock
    LatestOrgConfig latestOrgConfig;

    Set<String> otherTeamLogins = Set.of("user1", "user2", "other3", "other4");

    MockInstallation home_project_1;

    @BeforeEach
    @Override
    protected void setup() throws IOException {
        super.setup();
        home_project_1 = setupInstallationMocks(HOME_PROJECT_1);
        Log.info("START: ProjectManagerTest.setup()");

        OrganizationConfig orgConfig = loadYamlResource(
                "src/test/resources/cf-haus-organization.yml",
                OrganizationConfig.class);
        when(latestOrgConfig.getConfig()).thenReturn(orgConfig);

        // Mock the file content for organization config in primary repo
        mockFileContent(home_project_1, ProjectConfig.PATH,
                "src/test/resources/cf-haus-manager.yml");

        // trigger discovery to register installation
        triggerRepositoryDiscovery(DiscoveryAction.ADDED, project_org, true);

        when(taskState.lastRunOrNow(anyString())).thenReturn(Instant.parse("2026-08-24T11:00:00Z"));
        when(taskState.recordRun(anyString())).thenReturn(Instant.parse("2026-08-24T12:00:00Z"));
        when(taskState.shouldRun(anyString(), any())).thenReturn(false);

        // Trigger bootstrap completion to execute deferred reconciliation
        triggerBootstrapDiscovery(home_project_1);
        waitForQueue();

        clearInvocations(taskState, teamService);
    }

    @AfterEach
    void clear() {
        // Reset any internal state if the class has a reset method
        projectManager.reset();
    }

    @Test
    void testRepositoryEvents() throws IOException {
        GHRepository contactRepo = mockRepository("public-org/source", home_project_1.github());
        mockFileContent(contactRepo, "signatories.yaml",
                "src/test/resources/signatories.yml");

        mockTeam("test-org/cf-council", null);
        mockTeam("test-org/admin", null);
        mockTeam("test-org/team-quorum", null);

        // Trigger discovery to initialize manager
        triggerRepositoryDiscovery(DiscoveryAction.ADDED, home_project_1, false);

        waitForQueue();

        // Trigger discovery to remove configuration
        triggerRepositoryDiscovery(DiscoveryAction.REMOVED, home_project_1, false);

        waitForQueue();

        // This should be called only once (first event); second event cleans state
        verify(teamService, times(1)).syncCollaborators(any(),
                eq(home_project_1.repository()), any(), any(), any(), anyBoolean(), any());

        verify(teamService, times(1)).syncMembers(any(), eq("test-org/cf-council"), any(), any(), anyBoolean(), any());
        verify(teamService, times(1)).syncMembers(any(), eq("test-org/admin"), any(), any(), anyBoolean(), any());
        verify(teamService, never()).syncMembers(any(), eq("test-org/team-quorum"), any(), any(), anyBoolean(), any());
    }

    @Test
    void ignoresSameOrgRepoWhoseNameDoesNotStartWithProjectPrefix() throws IOException {
        var nonProjectRepo = mockRepository("test-org/my-project-tools", home_project_1.github());
        mockFileContent(nonProjectRepo, ProjectConfig.PATH,
                "src/test/resources/cf-haus-manager.yml");

        mockTeam("test-org/cf-council", null);
        mockTeam("test-org/admin", null);

        triggerRepositoryDiscovery(DiscoveryAction.ADDED, new MockInstallation(
                home_project_1.installationId(),
                home_project_1.github(),
                home_project_1.dql(),
                home_project_1.organization(),
                nonProjectRepo,
                home_project_1.queryContext()), false);

        waitForQueue();

        verify(teamService, never()).syncCollaborators(any(), eq(nonProjectRepo), any(), any(), any(), anyBoolean(), any());
        verify(teamService, never()).syncMembers(any(), eq("test-org/cf-council"), any(), any(), anyBoolean(), any());
        verify(teamService, never()).syncMembers(any(), eq("test-org/admin"), any(), any(), anyBoolean(), any());
    }

    @Test
    void testConfigurationUpdated() throws IOException {
        mockTeam("other-org/teamA", project_org.github(), otherTeamLogins);

        when(teamService.getTeamLogins(any(), any()))
                .thenReturn(otherTeamLogins);

        when(teamService.toRole(any(), any(), any(), any(), any(), any()))
                .thenReturn(RepositoryRole.from(Permission.PUSH));

        projectManager.processFileUpdate(taskGroup, new FileUpdate(
                ProjectConfig.PATH, FileUpdateType.MODIFIED,
                home_project_1.installationId(), home_project_1.repository(), home_project_1.github()));

        waitForQueue();

        // A change to watched memebership should trigger a sync
        projectManager.processMembershipUpdate(taskGroup,
                new MembershipUpdate(MembershipUpdateType.COLLABORATOR, HOME_PROJECT_1.orgName(),
                        new RepositoryEvent(
                                home_project_1.github(),
                                home_project_1.installationId(),
                                home_project_1.organization(),
                                home_project_1.repository(),
                                mockUser("test-user"),
                                ActionType.added,
                                EventType.member)));

        waitForQueue();

        // We should expect members of the team, and login from the config file
        Set<String> expectedLogins = Set.of("user1", "user2", "other3", "other4", "user4");
        String expectedRoleString = "push";

        // This should be called twice (file and membership events)
        verify(teamService, times(2)).syncCollaborators(any(),
                eq(home_project_1.repository()),
                argThat(actualRole -> actualRole.toString().equals(expectedRoleString)),
                eq(expectedLogins), any(), anyBoolean(), any());
        verify(taskState, never()).recordRun(ProjectManager.ME);
    }

    @Test
    void testMembershipUpdated() throws IOException {
        // A change to watched memebership should not trigger a sync if the
        // state has been cleared (e.g. after config removed)
        projectManager.processMembershipUpdate(taskGroup,
                new MembershipUpdate(MembershipUpdateType.COLLABORATOR, HOME_PROJECT_1.orgName(),
                        new RepositoryEvent(
                                home_project_1.github(),
                                home_project_1.installationId(),
                                home_project_1.organization(),
                                home_project_1.repository(),
                                mockUser("test-user"),
                                ActionType.added,
                                EventType.member)));

        // This shouldn't be called. The state is gone.
        verify(teamService, times(0)).syncCollaborators(any(),
                eq(home_project_1.repository()), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void bootstrapCompleteRecordsRunStateForFullScopeRefresh() {
        when(taskState.shouldRun(anyString(), any())).thenReturn(true);

        triggerBootstrapDiscovery(home_project_1);

        waitForQueue();

        verify(taskState).recordRun(ProjectManager.ME);
    }

    @Test
    void targetedFileUpdateDoesNotRecordFullScopeRunState() throws IOException {
        mockTeam("other-org/teamA", project_org.github(), otherTeamLogins);

        when(teamService.getTeamLogins(any(), any()))
                .thenReturn(otherTeamLogins);

        when(teamService.toRole(any(), any(), any(), any(), any(), any()))
                .thenReturn(RepositoryRole.from(Permission.PUSH));

        projectManager.processFileUpdate(taskGroup, new FileUpdate(
                ProjectConfig.PATH, FileUpdateType.MODIFIED,
                home_project_1.installationId(), home_project_1.repository(), home_project_1.github()));

        waitForQueue();

        verify(taskState, never()).recordRun(ProjectManager.ME);
        verify(teamService, times(1)).syncCollaborators(any(),
                eq(home_project_1.repository()), any(), any(), any(), anyBoolean(), any());
    }
}
