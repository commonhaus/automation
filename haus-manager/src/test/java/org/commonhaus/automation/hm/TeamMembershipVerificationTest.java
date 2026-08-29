package org.commonhaus.automation.hm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;

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

    @BeforeEach
    @Override
    protected void setup() throws IOException {
        super.setup();
        setupInstallationMocks(HOME_PROJECT_1);
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

        // Config-load-time validation (normally run from readProjectConfig)
        TeamOrgValidator.validateAndNotify(ctx, ProjectManager.ME, state, projectConfig,
                "test-org", orgConfig.teamMembershipVerificationMode(), orgConfig.emailNotifications().dryRun(), true);

        assertThat(latestOrgConfig.getConfig().teamMembershipVerification()).isEqualTo("dryRun");
        assertThat(latestProjectConfig.getProjectConfigState(state.repoFullName())).isSameAs(state);

        Awaitility.await().untilAsserted(() -> {
            assertThat(mailbox.getMailsSentTo("org-dryrun@test.org")).hasSize(1);
            String body = mailbox.getMailsSentTo("org-dryrun@test.org").get(0).getText();
            assertThat(body).contains("other-org/teamB");
            assertThat(body).contains("do not match");
            assertThat(body).contains("add the missing organization(s)");
        });
        assertThat(mailbox.getMailsSentTo("project-mismatch-errors@test.org")).isEmpty();
    }

    @Test
    void dryRunModeSkipsBlankTargetAndSendsDryRunEmail() throws IOException {
        OrganizationConfig orgConfig = loadYamlResource(
                "src/test/resources/cf-haus-organization-team-verify-dryrun.yml",
                OrganizationConfig.class);
        ProjectConfig projectConfig = loadYamlResource(
                "src/test/resources/cf-haus-manager-team-malformed.yml",
                ProjectConfig.class);
        ProjectConfigState state = registerProjectState(orgConfig, projectConfig);

        TeamOrgValidator.validateAndNotify(ctx, ProjectManager.ME, state, projectConfig,
                "test-org", orgConfig.teamMembershipVerificationMode(), orgConfig.emailNotifications().dryRun(), true);

        assertThat(state.blockedTeams()).containsExactly("test-org/");

        Awaitility.await().untilAsserted(() -> {
            assertThat(mailbox.getMailsSentTo("org-dryrun@test.org")).hasSize(1);
            String body = mailbox.getMailsSentTo("org-dryrun@test.org").get(0).getText();
            assertThat(body).contains("bad team!");
            assertThat(body).contains("test-org/");
            assertThat(body).contains("do not match");
            assertThat(body).contains("add the missing organization(s)");
        });
        assertThat(mailbox.getMailsSentTo("project-malformed-errors@test.org")).isEmpty();
        assertThat(latestOrgConfig.getConfig().teamMembershipVerification()).isEqualTo("dryRun");
    }

    @Test
    void warnModeSendsProjectErrorEmailAndStillSyncsMismatchedTeams() throws IOException {
        OrganizationConfig orgConfig = loadYamlResource(
                "src/test/resources/cf-haus-organization-team-verify-warn.yml",
                OrganizationConfig.class);
        ProjectConfig projectConfig = loadYamlResource(
                "src/test/resources/cf-haus-manager-team-org-mismatch.yml",
                ProjectConfig.class);
        ProjectConfigState state = registerProjectState(orgConfig, projectConfig);

        TeamOrgValidator.validateAndNotify(ctx, ProjectManager.ME, state, projectConfig,
                "test-org", orgConfig.teamMembershipVerificationMode(), orgConfig.emailNotifications().dryRun(), true);

        assertThat(state.blockedTeams()).isEmpty();

        Awaitility.await().untilAsserted(() -> {
            assertThat(mailbox.getMailsSentTo("project-mismatch-errors@test.org")).hasSize(1);
            String body = mailbox.getMailsSentTo("project-mismatch-errors@test.org").get(0).getText();
            assertThat(body).contains("other-org/teamB");
            assertThat(body).contains("do not match");
            assertThat(body).contains("add the missing organization(s)");
        });
        assertThat(mailbox.getMailsSentTo("org-dryrun@test.org")).isEmpty();
        assertThat(latestOrgConfig.getConfig().teamMembershipVerification()).isEqualTo("warn");
    }

    @Test
    void errorModeSkipsMismatchedTeamAndSendsProjectErrorEmail() throws IOException {
        OrganizationConfig orgConfig = loadYamlResource(
                "src/test/resources/cf-haus-organization-team-verify-error.yml",
                OrganizationConfig.class);
        ProjectConfig projectConfig = loadYamlResource(
                "src/test/resources/cf-haus-manager-team-org-mismatch.yml",
                ProjectConfig.class);
        ProjectConfigState state = registerProjectState(orgConfig, projectConfig);

        TeamOrgValidator.validateAndNotify(ctx, ProjectManager.ME, state, projectConfig,
                "test-org", orgConfig.teamMembershipVerificationMode(), orgConfig.emailNotifications().dryRun(), true);

        assertThat(state.blockedTeams()).containsExactly("other-org/teamB");

        Awaitility.await().untilAsserted(() -> {
            assertThat(mailbox.getMailsSentTo("project-mismatch-errors@test.org")).hasSize(1);
            String body = mailbox.getMailsSentTo("project-mismatch-errors@test.org").get(0).getText();
            assertThat(body).contains("other-org/teamB");
            assertThat(body).contains("do not match");
            assertThat(body).contains("add the missing organization(s)");
        });
        assertThat(mailbox.getMailsSentTo("org-dryrun@test.org")).isEmpty();
        assertThat(latestOrgConfig.getConfig().teamMembershipVerification()).isEqualTo("error");
    }

    @Test
    void errorModeSkipsBlankAndMismatchedTargetsButStillReadsMismatchedCollaboratorSource() throws IOException {
        OrganizationConfig orgConfig = loadYamlResource(
                "src/test/resources/cf-haus-organization-team-verify-error.yml",
                OrganizationConfig.class);
        ProjectConfig projectConfig = loadYamlResource(
                "src/test/resources/cf-haus-manager-team-malformed.yml",
                ProjectConfig.class);
        ProjectConfigState state = registerProjectState(orgConfig, projectConfig);

        TeamOrgValidator.validateAndNotify(ctx, ProjectManager.ME, state, projectConfig,
                "test-org", orgConfig.teamMembershipVerificationMode(), orgConfig.emailNotifications().dryRun(), true);

        assertThat(state.blockedTeams()).containsExactlyInAnyOrder("test-org/", "other-org/bad team!");

        Awaitility.await().untilAsserted(() -> {
            assertThat(mailbox.getMailsSentTo("project-malformed-errors@test.org")).hasSize(1);
            String body = mailbox.getMailsSentTo("project-malformed-errors@test.org").get(0).getText();
            assertThat(body).contains("bad team!");
            assertThat(body).contains("test-org/");
            assertThat(body).contains("do not match");
            assertThat(body).contains("add the missing organization(s)");
        });
        assertThat(mailbox.getMailsSentTo("org-dryrun@test.org")).isEmpty();
        assertThat(latestOrgConfig.getConfig().teamMembershipVerification()).isEqualTo("error");
    }

    @Test
    void absentGithubOrganizationsTreatsAllReferencedTeamsAsViolations() throws IOException {
        OrganizationConfig orgConfig = loadYamlResource(
                "src/test/resources/cf-haus-organization-team-verify-error.yml",
                OrganizationConfig.class);
        ProjectConfig projectConfig = loadYamlResource(
                "src/test/resources/cf-haus-manager-team-no-orgs.yml",
                ProjectConfig.class);
        ProjectConfigState state = registerProjectState(orgConfig, projectConfig);

        TeamOrgValidator.validateAndNotify(ctx, ProjectManager.ME, state, projectConfig,
                "test-org", orgConfig.teamMembershipVerificationMode(), orgConfig.emailNotifications().dryRun(), true);

        assertThat(state.blockedTeams()).containsExactlyInAnyOrder("test-org/cf-council", "other-org/teamB");

        Awaitility.await().untilAsserted(() -> {
            assertThat(mailbox.getMailsSentTo("project-no-orgs-errors@test.org")).hasSize(1);
            String body = mailbox.getMailsSentTo("project-no-orgs-errors@test.org").get(0).getText();
            assertThat(body).contains("test-org/cf-council");
            assertThat(body).contains("other-org/teamB");
            assertThat(body).contains("do not match");
            assertThat(body).contains("add the missing organization(s)");
        });
        assertThat(mailbox.getMailsSentTo("org-dryrun@test.org")).isEmpty();
        assertThat(latestOrgConfig.getConfig().teamMembershipVerification()).isEqualTo("error");
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
