package org.commonhaus.automation.hm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import jakarta.inject.Inject;

import org.awaitility.Awaitility;
import org.commonhaus.automation.hm.ProjectManager.ProjectConfigState;
import org.commonhaus.automation.hm.config.LatestOrgConfig;
import org.commonhaus.automation.hm.config.LatestProjectConfig;
import org.commonhaus.automation.hm.config.OrganizationConfig;
import org.commonhaus.automation.hm.config.ProjectConfig;
import org.commonhaus.automation.hm.github.HausManagerTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHOrganization.Permission;
import org.kohsuke.github.GHOrganization.RepositoryRole;
import org.kohsuke.github.GHRepository;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class TeamMembershipVerificationTest extends HausManagerTestBase {
    static final String TASK_GROUP = ProjectManager.repoNametoTaskGroup(HOME_PROJECT_1.repoFullName());

    @Inject
    ProjectManager projectManager;

    @InjectMock
    LatestOrgConfig latestOrgConfig;

    @InjectMock
    LatestProjectConfig latestProjectConfig;

    MockInstallation homeProject;

    @BeforeEach
    @Override
    protected void setup() throws IOException {
        super.setup();
        homeProject = setupInstallationMocks(HOME_PROJECT_1);
        clearInvocations(teamService);
    }

    @AfterEach
    void clear() {
        projectManager.reset();
    }

    @Test
    void dryRunModeSendsOrgDryRunEmailAndStillSyncsMismatchedTeams() throws IOException {
        OrganizationConfig orgConfig = loadYamlResource(
                "src/test/resources/cf-haus-organization-team-verify-dryrun.yml",
                OrganizationConfig.class);
        ProjectConfig projectConfig = loadYamlResource(
                "src/test/resources/cf-haus-manager-team-org-mismatch.yml",
                ProjectConfig.class);
        ProjectConfigState state = registerProjectState(orgConfig, projectConfig);

        GHRepository contactRepo = mockRepository("public-org/source", homeProject.github());
        mockFileContent(contactRepo, "signatories.yaml", "src/test/resources/signatories.yml");

        when(teamService.getTeamLogins(any(), eq("other-org/teamA")))
                .thenReturn(Set.of("user1", "user2", "other3", "other4"));
        when(teamService.toRole(any(), any(), any(), any(), any(), any()))
                .thenReturn(RepositoryRole.from(Permission.PUSH));

        projectManager.reconcile(TASK_GROUP);
        waitForQueue();

        verify(teamService).syncMembers(any(), eq("test-org/cf-council"), any(), any(), anyBoolean(), any());
        verify(teamService).syncMembers(any(), eq("other-org/teamB"), any(), any(), anyBoolean(), any());

        Awaitility.await().untilAsserted(() -> {
            assertThat(mailbox.getMailsSentTo("org-dryrun@test.org")).hasSize(1);
            assertThat(mailbox.getMailsSentTo("org-dryrun@test.org").get(0).getText())
                    .contains("other-org/teamB");
        });
        assertThat(mailbox.getMailsSentTo("project-mismatch-errors@test.org")).isEmpty();
        assertThat(latestOrgConfig.getConfig().teamMembershipVerification()).isEqualTo("dryRun");
        assertThat(latestProjectConfig.getProjectConfigState(state.repoFullName())).isSameAs(state);
    }

    @Test
    void dryRunModeSkipsMalformedTargetsButStillReadsMalformedCollaboratorSource() throws IOException {
        OrganizationConfig orgConfig = loadYamlResource(
                "src/test/resources/cf-haus-organization-team-verify-dryrun.yml",
                OrganizationConfig.class);
        ProjectConfig projectConfig = loadYamlResource(
                "src/test/resources/cf-haus-manager-team-malformed.yml",
                ProjectConfig.class);
        registerProjectState(orgConfig, projectConfig);

        GHRepository contactRepo = mockRepository("public-org/source", homeProject.github());
        mockFileContent(contactRepo, "signatories.yaml", "src/test/resources/signatories.yml");

        when(teamService.getTeamLogins(any(), eq("test-org/bad team!")))
                .thenReturn(null);
        when(teamService.toRole(any(), any(), any(), any(), any(), any()))
                .thenReturn(RepositoryRole.from(Permission.PUSH));

        projectManager.reconcile(TASK_GROUP);
        waitForQueue();

        verify(teamService).syncMembers(any(), eq("test-org/cf-council"), any(), any(), anyBoolean(), any());
        verify(teamService).syncMembers(any(), eq("test-org/admin"), any(), any(), anyBoolean(), any());
        verify(teamService, never()).syncMembers(any(), eq("test-org/"), any(), any(), anyBoolean(), any());
        verify(teamService, never()).syncMembers(any(), eq("test-org/bad team!"), any(), any(), anyBoolean(), any());
        verify(teamService).getTeamLogins(any(), eq("test-org/bad team!"));

        Awaitility.await().untilAsserted(() -> {
            assertThat(mailbox.getMailsSentTo("org-dryrun@test.org")).hasSize(1);
            String body = mailbox.getMailsSentTo("org-dryrun@test.org").get(0).getText();
            assertThat(body).contains("bad team!");
            assertThat(body).contains("test-org/");
        });
        assertThat(mailbox.getMailsSentTo("project-malformed-errors@test.org")).isEmpty();
        assertThat(latestOrgConfig.getConfig().teamMembershipVerification()).isEqualTo("dryRun");
    }

    private ProjectConfigState registerProjectState(OrganizationConfig orgConfig, ProjectConfig projectConfig) {
        when(latestOrgConfig.getConfig()).thenReturn(orgConfig);
        when(latestOrgConfig.projectNameToRepoFullName(any(), eq("one"))).thenReturn(HOME_PROJECT_1.repoFullName());

        ProjectConfigState state = new ProjectConfigState(
                TASK_GROUP,
                () -> {
                },
                HOME_PROJECT_1.repoFullName(),
                HOME_PROJECT_1.installId(),
                projectConfig);

        projectManager.taskGroupToState.put(TASK_GROUP, state);

        when(latestProjectConfig.getProjectConfigState(HOME_PROJECT_1.repoFullName())).thenReturn(state);
        when(latestProjectConfig.getAllProjects()).thenReturn(List.of(state));
        return state;
    }
}
