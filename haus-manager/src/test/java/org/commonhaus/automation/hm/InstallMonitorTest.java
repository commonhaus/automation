package org.commonhaus.automation.hm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;

import jakarta.inject.Inject;

import org.commonhaus.automation.hm.ProjectManager.ProjectConfigState;
import org.commonhaus.automation.hm.config.LatestOrgConfig;
import org.commonhaus.automation.hm.config.LatestProjectConfig;
import org.commonhaus.automation.hm.config.OrganizationConfig;
import org.commonhaus.automation.hm.config.ProjectConfig;
import org.commonhaus.automation.hm.github.HausManagerTestBase;
import org.commonhaus.automation.hm.github.TestScopedInstallationMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.logging.Log;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class InstallMonitorTest extends HausManagerTestBase {

    @Inject
    InstallMonitor installMonitor;

    @Inject
    TestScopedInstallationMap installationMap;

    @InjectMock
    LatestOrgConfig latestOrgConfig;

    @InjectMock
    LatestProjectConfig latestProjectConfig;

    private OrganizationConfig mockOrgConfig;

    MockInstallation home_project_1;
    private ProjectConfigState mockProjectState1;

    MockInstallation home_project_2;
    private ProjectConfigState mockProjectState2;

    @BeforeEach
    @Override
    protected void setup() throws IOException {
        super.setup();
        home_project_1 = setupInstallationMocks(HOME_PROJECT_1);
        home_project_2 = setupInstallationMocks(HOME_PROJECT_2);

        Log.info("START: InstallationMonitorTest.setup()");

        // Create mock org config
        mockOrgConfig = loadYamlResource(
                "src/test/resources/cf-haus-organization-installations.yml",
                OrganizationConfig.class);
        when(latestOrgConfig.getConfig()).thenReturn(mockOrgConfig);
        when(latestOrgConfig.projectNameToRepoFullName(any(), eq("one")))
                .thenReturn(HOME_PROJECT_1.repoFullName());
        when(latestOrgConfig.projectNameToRepoFullName(any(), eq("two")))
                .thenReturn(HOME_PROJECT_2.repoFullName());

        // Project 1 mock config/state
        var config1 = loadYamlResource(
                "src/test/resources/cf-haus-manager-installations-p1.yml",
                ProjectConfig.class);
        mockProjectState1 = new ProjectConfigState(
                ProjectManager.repoNametoTaskGroup(HOME_PROJECT_1.repoFullName()),
                () -> {
                },
                HOME_PROJECT_1.repoFullName(),
                HOME_PROJECT_1.installId(),
                config1);
        when(latestProjectConfig.getProjectConfigState(HOME_PROJECT_1.repoFullName()))
                .thenReturn(mockProjectState1);

        // Project 2 mock config/state
        var config2 = loadYamlResource(
                "src/test/resources/cf-haus-manager-installations-p2.yml",
                ProjectConfig.class);
        mockProjectState2 = new ProjectConfigState(
                ProjectManager.repoNametoTaskGroup(HOME_PROJECT_2.repoFullName()),
                () -> {
                },
                HOME_PROJECT_2.repoFullName(),
                HOME_PROJECT_2.installId(),
                config2);
        when(latestProjectConfig.getProjectConfigState(HOME_PROJECT_2.repoFullName()))
                .thenReturn(mockProjectState2);
        when(latestProjectConfig.getAllProjects())
                .thenReturn(List.of(mockProjectState1, mockProjectState2));
    }

    @AfterEach
    protected void resetMap() {
        installationMap.reset();
    }

    @Test
    void testThreeWayReconciliation() {
        Log.info("TEST: Comprehensive 3-way reconciliation covering all scenarios");

        // Setup complex scenario:
        // - test-org-one: assigned to project-one, declared by project-one, installed ✓
        // - test-org-two: assigned to project-two, NOT declared by project-two, installed (mismatch)
        // - test-org-three: NOT assigned, declared by project-one, installed (mismatch)
        // - test-org-four: assigned to project-two, declared by project-two, NOT installed (missing)
        // - orphan-org: NOT assigned, NOT declared, installed (unmapped)

        installationMap.addTestOrg(88888, "main-org/repo");
        installationMap.addTestOrg(12345, "test-org-one/repo");
        installationMap.addTestOrg(123456, "test-org-two/repo");
        installationMap.addTestOrg(11111, "test-org-three/repo");
        installationMap.addTestOrg(99999, "orphan-org/repo");

        // Modify configs
        // Org assigns test-org-four to project-two
        var assets = mockOrgConfig.projects().assetsForProject("two");
        assets.githubOrganizations().add("test-org-four");

        // Project-one declares test-org-three (not assigned to them)
        mockProjectState1.projectConfig().githubOrganizations().add("test-org-three");

        // Project-two doesn't declare test-org-two (but assigned to them)
        mockProjectState2.projectConfig().githubOrganizations().remove("test-org-two");
        // Project-two declares test-org-four (assigned to them but not installed)
        mockProjectState2.projectConfig().githubOrganizations().add("test-org-four");

        // Execute
        installMonitor.checkInstallations(true);
        waitForQueue();

        // Verify: Project-one gets error about test-org-three
        var project1Errors = mailbox.getMailsSentTo("errors@project1.dev");
        assertThat(project1Errors).hasSize(1);
        assertThat(project1Errors.get(0).getText()).contains("test-org-three");
        assertThat(project1Errors.get(0).getText()).contains("not assigned to your project");

        // Verify: Project-two gets errors about test-org-two (should add) and test-org-four (not installed)
        var project2Errors = mailbox.getMailsSentTo("errors@project2.dev");
        assertThat(project2Errors).hasSize(1);
        assertThat(project2Errors.get(0).getText()).contains("test-org-two");
        assertThat(project2Errors.get(0).getText()).contains("assigned to your project but not in your configuration");
        assertThat(project2Errors.get(0).getText()).contains("test-org-four");
        assertThat(project2Errors.get(0).getText()).contains("Missing Installations");

        // Verify: Summary shows valid (test-org-one) and unmapped (orphan-org)
        var auditEmails = mailbox.getMailsSentTo("audit@test.org");
        assertThat(auditEmails).hasSize(1);
        assertThat(auditEmails.get(0).getText()).contains("Configured Organizations");
        assertThat(auditEmails.get(0).getText()).contains("test-org-one");
        assertThat(auditEmails.get(0).getText()).contains("Unmapped Organizations");
        assertThat(auditEmails.get(0).getText()).contains("orphan-org");
        assertThat(auditEmails.get(0).getText()).doesNotContain("main-org");
        // Should not contain problematic orgs in configured section
        assertThat(auditEmails.get(0).getText().split("Configured Organizations")[1].split("Unmapped")[0])
                .doesNotContain("test-org-two")
                .doesNotContain("test-org-three")
                .doesNotContain("test-org-four");
    }
}
