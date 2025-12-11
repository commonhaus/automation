package org.commonhaus.automation.hm;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import jakarta.inject.Inject;

import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.hm.ProjectManager.ProjectConfigState;
import org.commonhaus.automation.hm.config.LatestOrgConfig;
import org.commonhaus.automation.hm.config.LatestProjectConfig;
import org.commonhaus.automation.hm.config.OrganizationConfig;
import org.commonhaus.automation.hm.config.OrganizationConfig.CollaboratorMonitorConfig;
import org.commonhaus.automation.hm.config.ProjectConfig;
import org.commonhaus.automation.hm.config.ProjectConfig.CollaboratorSync;
import org.commonhaus.automation.hm.github.HausManagerTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHOrganization.Permission;
import org.kohsuke.github.GHOrganization.RepositoryRole;
import org.kohsuke.github.GHRepository;
import org.mockito.Mockito;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.logging.Log;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Test for CollaboratorMonitor using mocks for configs
 */
@QuarkusTest
@GitHubAppTest
public class CollaboratorMonitorTest extends HausManagerTestBase {

    @Inject
    CollaboratorMonitor collaboratorMonitor;

    @InjectMock
    LatestOrgConfig latestOrgConfig;

    @InjectMock
    LatestProjectConfig latestProjectConfig;

    // Mock data
    Set<String> project1Collaborators = Set.of("user1", "user2", "user3");
    Set<String> project2Collaborators = Set.of("user2", "user4", "user5");
    Set<String> allExpectedCollaborators = Set.of("user1", "user2", "user3", "user4", "user5");

    GHRepository allCollaboratorsRepo;
    GHRepository project1Repo;
    GHRepository project2Repo;

    @BeforeEach
    @Override
    public void setup() throws IOException {
        super.setup();
        Log.info("START: CollaboratorMonitorTest.setup()");

        // Mock GH repositories using base class helper
        allCollaboratorsRepo = mockRepository("test-org/all-collaborators", hausMocks.github());

        var project1 = setupInstallationMocks(HOME_PROJECT_1);
        project1Repo = project1.repository();

        var project2 = setupInstallationMocks(HOME_PROJECT_2);
        project2Repo = project2.repository();

        // Mock organization config with collaboratorMonitor enabled
        OrganizationConfig orgConfig = mockOrgConfig(true, false, List.of());
        when(latestOrgConfig.getConfig()).thenReturn(orgConfig);

        // Mock project configs
        ProjectConfig projectConfig1 = Mockito.mock(ProjectConfig.class);
        CollaboratorSync collab1 = new CollaboratorSync("triage", "test-org/team1", List.of(), List.of());
        when(projectConfig1.collaboratorSync()).thenReturn(collab1);

        ProjectConfig projectConfig2 = Mockito.mock(ProjectConfig.class);
        CollaboratorSync collab2 = new CollaboratorSync("triage", "test-org/team2", List.of(), List.of());
        when(projectConfig2.collaboratorSync()).thenReturn(collab2);

        ProjectConfigState state1 = mockProjectState(projectConfig1, "test-org/project-one");
        ProjectConfigState state2 = mockProjectState(projectConfig2, "test-org/project-two");

        when(latestProjectConfig.getAllProjects()).thenReturn(List.of(state1, state2));

        // Mock team service
        when(teamService.getCollaboratorLogins(any(), eq(project1Repo))).thenReturn(project1Collaborators);
        when(teamService.getCollaboratorLogins(any(), eq(project2Repo))).thenReturn(project2Collaborators);
        when(teamService.toRole(any(), any(), any(), any(), any(), any()))
                .thenReturn(RepositoryRole.from(Permission.TRIAGE));

        // trigger discovery to register installation
        triggerRepositoryDiscovery(DiscoveryAction.ADDED, hausMocks, true);
        triggerRepositoryDiscovery(DiscoveryAction.ADDED, project1, true);
        triggerRepositoryDiscovery(DiscoveryAction.ADDED, project2, true);

        // Trigger bootstrap completion to execute deferred reconciliation
        triggerBootstrapDiscovery(hausMocks);
    }

    private OrganizationConfig mockOrgConfig(boolean enabled, boolean dryRun, List<String> ignoreUsers) {
        OrganizationConfig orgConfig = Mockito.mock(OrganizationConfig.class);
        CollaboratorMonitorConfig monitorConfig = new CollaboratorMonitorConfig(
                enabled,
                dryRun,
                "test-org/all-collaborators",
                "triage",
                ignoreUsers);
        when(orgConfig.isCollaboratorMonitorEnabled()).thenReturn(enabled);
        when(orgConfig.collaboratorMonitor()).thenReturn(monitorConfig);
        when(orgConfig.emailNotifications()).thenReturn(null);
        return orgConfig;
    }

    private ProjectConfigState mockProjectState(ProjectConfig config, String repoFullName) {
        ProjectConfigState state = Mockito.mock(ProjectConfigState.class);
        when(state.projectConfig()).thenReturn(config);
        when(state.repoFullName()).thenReturn(repoFullName);
        return state;
    }

    @Test
    void testCollaboratorMonitorGathersAndSyncs() {
        Log.info("TEST: testCollaboratorMonitorGathersAndSyncs");

        collaboratorMonitor.refreshCollaborators(true);
        waitForQueue();

        // Verify syncCollaborators was called with all expected collaborators
        verify(teamService, times(1)).syncCollaborators(
                any(),
                eq(allCollaboratorsRepo),
                argThat(role -> role.toString().equals("triage")),
                eq(allExpectedCollaborators), // 5 unique users
                any(),
                eq(false), // not dry run
                any());
    }

    @Test
    void testCollaboratorMonitorSkipsWhenDisabled() {
        Log.info("TEST: testCollaboratorMonitorSkipsWhenDisabled");

        // Mock disabled config
        OrganizationConfig disabledConfig = mockOrgConfig(false, false, List.of());
        when(latestOrgConfig.getConfig()).thenReturn(disabledConfig);

        collaboratorMonitor.refreshCollaborators(true);
        waitForQueue();

        // Verify syncCollaborators was NOT called
        verify(teamService, never()).syncCollaborators(
                any(),
                eq(allCollaboratorsRepo),
                any(),
                any(),
                any(),
                anyBoolean(),
                any());
    }

    @Test
    void testCollaboratorMonitorSkipsProjectsWithoutCollaboratorSync() {
        Log.info("TEST: testCollaboratorMonitorSkipsProjectsWithoutCollaboratorSync");

        // Mock project 2 without collaboratorSync
        ProjectConfig projectConfig2 = Mockito.mock(ProjectConfig.class);
        when(projectConfig2.collaboratorSync()).thenReturn(null); // No collaboratorSync

        ProjectConfigState state2 = mockProjectState(projectConfig2, "test-org/project-two");

        // Update to only have project1 with config, project2 without
        ProjectConfig projectConfig1 = Mockito.mock(ProjectConfig.class);
        CollaboratorSync collab1 = new CollaboratorSync("triage", "test-org/team1", List.of(), List.of());
        when(projectConfig1.collaboratorSync()).thenReturn(collab1);
        ProjectConfigState state1 = mockProjectState(projectConfig1, "test-org/project-one");

        when(latestProjectConfig.getAllProjects()).thenReturn(List.of(state1, state2));

        collaboratorMonitor.refreshCollaborators(true);
        waitForQueue();

        // Verify syncCollaborators was called with only project1's collaborators
        verify(teamService, times(1)).syncCollaborators(
                any(),
                eq(allCollaboratorsRepo),
                any(),
                eq(project1Collaborators), // Only project1 collaborators
                any(),
                anyBoolean(),
                any());
    }

    @Test
    void testCollaboratorMonitorHandlesUninitializedProject() {
        Log.info("TEST: testCollaboratorMonitorHandlesUninitializedProject");

        // Mock uninitialized project state
        when(latestProjectConfig.getAllProjects()).thenReturn(List.of(ProjectManager.EMPTY));

        collaboratorMonitor.refreshCollaborators(true);
        waitForQueue();

        // Verify syncCollaborators was NOT called due to error
        verify(teamService, never()).syncCollaborators(
                any(),
                any(),
                any(),
                any(),
                any(),
                anyBoolean(),
                any());
    }

    @Test
    void testCollaboratorMonitorDeduplicatesCollaborators() {
        Log.info("TEST: testCollaboratorMonitorDeduplicatesCollaborators");

        // Both projects have "user2" - verify deduplication
        collaboratorMonitor.refreshCollaborators(true);
        waitForQueue();

        // Verify set contains exactly 5 unique users
        verify(teamService, times(1)).syncCollaborators(
                any(),
                eq(allCollaboratorsRepo),
                any(),
                argThat(logins -> logins.size() == 5 && logins.equals(allExpectedCollaborators)),
                any(),
                anyBoolean(),
                any());
    }

    @Test
    void testCollaboratorMonitorRespectsIgnoreUsers() {
        Log.info("TEST: testCollaboratorMonitorRespectsIgnoreUsers");

        // Mock config with ignoreUsers
        OrganizationConfig configWithIgnore = mockOrgConfig(true, false, List.of("bot-user"));
        when(latestOrgConfig.getConfig()).thenReturn(configWithIgnore);

        collaboratorMonitor.refreshCollaborators(true);
        waitForQueue();

        // Verify ignoreUsers list is passed through
        verify(teamService, times(1)).syncCollaborators(
                any(),
                eq(allCollaboratorsRepo),
                any(),
                any(),
                argThat(ignoreUsers -> ignoreUsers.contains("bot-user")),
                anyBoolean(),
                any());
    }
}
