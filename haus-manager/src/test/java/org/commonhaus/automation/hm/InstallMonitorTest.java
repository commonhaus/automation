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
        // - main-org: org-managed (in githubOrganizations), NOT installed (missing org-managed)
        // - test-org-one: assigned to project-one, declared by project-one, installed ✓
        // - test-org-two: assigned to project-two, declared by project-two, installed ✓
        // - test-org-three: NOT assigned, declared by project-one, installed (mismatch → toRemove)
        // - test-org-four: assigned to project-two, NOT declared by project-two, NOT installed (mismatch + missing)
        // - orphan-org: NOT assigned, NOT declared, installed (unmapped)

        // Note: main-org deliberately NOT added to installationMap
        installationMap.addTestOrg(12345, "test-org-one/repo");
        installationMap.addTestOrg(123456, "test-org-two/repo");
        installationMap.addTestOrg(11111, "test-org-three/repo");
        installationMap.addTestOrg(99999, "orphan-org/repo");

        // Modify configs
        // Org assigns test-org-four to project-two (not declared by project, not installed)
        var assets = mockOrgConfig.projects().assetsForProject("two");
        assets.githubOrganizations().add("test-org-four");

        // Project-one declares test-org-three (not assigned to them)
        mockProjectState1.projectConfig().githubOrganizations().add("test-org-three");

        // Execute
        installMonitor.checkInstallations(true);
        waitForQueue();

        // Verify: Project-one gets error about test-org-three (declared but not assigned → toRemove)
        var project1Errors = mailbox.getMailsSentTo("errors@project1.dev");
        assertThat(project1Errors).hasSize(1);
        assertThat(project1Errors.get(0).getText()).contains("test-org-three");
        assertThat(project1Errors.get(0).getText()).contains("not in organization records");

        // Verify: Project-two gets error about test-org-four
        //   - config mismatch: assigned but not declared (toAdd)
        //   - not installed: HausManager missing
        var project2Errors = mailbox.getMailsSentTo("errors@project2.dev");
        assertThat(project2Errors).hasSize(1);
        assertThat(project2Errors.get(0).getText()).contains("test-org-four");
        assertThat(project2Errors.get(0).getText()).contains("missing from your project configuration");
        assertThat(project2Errors.get(0).getText()).contains("HausManager is not installed");

        // Verify: Audit summary is grouped by project name with status indicators
        var auditEmails = mailbox.getMailsSentTo("audit@test.org");
        assertThat(auditEmails).hasSize(1);
        String auditText = auditEmails.get(0).getText();

        // Organization section: main-org is org-managed but NOT installed
        assertThat(auditText).contains("## Organization");
        assertThat(auditText).contains("❌ main-org");

        // Project sections use project names (not repo full names)
        assertThat(auditText).contains("## one");
        assertThat(auditText).contains("✅ test-org-one");
        // test-org-three declared by project but not in org config — shown under project with ❓
        assertThat(auditText).contains("❓ test-org-three");
        assertThat(auditText).contains("not in organization records");

        assertThat(auditText).contains("## two");
        assertThat(auditText).contains("✅ test-org-two");
        // test-org-four: assigned in org config but not installed
        assertThat(auditText).contains("❌ test-org-four");
        assertThat(auditText).contains("not installed");
        assertThat(auditText).doesNotContain("test-org/project-one");
        assertThat(auditText).doesNotContain("test-org/project-two");

        // Unmapped section
        assertThat(auditText).contains("## Unmapped");
        assertThat(auditText).contains("❓ orphan-org");
    }

    @Test
    void testNoIssuesWhenAllValid() {
        Log.info("TEST: No issue emails when all orgs are properly configured");

        // Setup: everything matches, no orphans
        installationMap.addTestOrg(88888, "main-org/repo");
        installationMap.addTestOrg(12345, "test-org-one/repo");
        installationMap.addTestOrg(123456, "test-org-two/repo");

        // Execute
        installMonitor.checkInstallations(true);
        waitForQueue();

        // No project error emails
        var project1Errors = mailbox.getMailsSentTo("errors@project1.dev");
        assertThat(project1Errors).isEmpty();
        var project2Errors = mailbox.getMailsSentTo("errors@project2.dev");
        assertThat(project2Errors).isEmpty();
        var orgErrors = mailbox.getMailsSentTo("errors@test.org");
        assertThat(orgErrors).isEmpty();

        // Audit email uses project names, not repo full names
        var auditEmails = mailbox.getMailsSentTo("audit@test.org");
        assertThat(auditEmails).hasSize(1);
        String auditText = auditEmails.get(0).getText();
        assertThat(auditText).contains("## Organization");
        assertThat(auditText).contains("main-org");
        assertThat(auditText).contains("## one");
        assertThat(auditText).contains("test-org-one");
        assertThat(auditText).contains("## two");
        assertThat(auditText).contains("test-org-two");
        assertThat(auditText).doesNotContain("Unmapped");
    }

    @Test
    void testOrgManagedConflict() {
        Log.info("TEST: Project declaring an org-managed organization shows warning in audit");

        // Setup: project-one also declares main-org (which is org-managed)
        installationMap.addTestOrg(88888, "main-org/repo");
        installationMap.addTestOrg(12345, "test-org-one/repo");
        installationMap.addTestOrg(123456, "test-org-two/repo");

        mockProjectState1.projectConfig().githubOrganizations().add("main-org");

        // Execute
        installMonitor.checkInstallations(true);
        waitForQueue();

        // No project error emails for org-managed conflicts
        var project1Errors = mailbox.getMailsSentTo("errors@project1.dev");
        assertThat(project1Errors).isEmpty();

        // Audit summary shows main-org with issues indicator
        var auditEmails = mailbox.getMailsSentTo("audit@test.org");
        assertThat(auditEmails).hasSize(1);
        String auditText = auditEmails.get(0).getText();
        assertThat(auditText).contains("Organization");
        assertThat(auditText).contains("main-org");
        // main-org should show as having issues (org-project conflict)
        assertThat(auditText).contains("⚠️");
    }

    @Test
    void testSharedRepositoryProjectName() throws IOException {
        Log.info("TEST: Shared repository should use repository name, not YAML key, for display name");

        // Setup: Use shared repository configuration where projectA and projectB
        // both reference project-shared, with projectB listed after projectA
        mockOrgConfig = loadYamlResource(
                "src/test/resources/cf-haus-organization-shared-repo.yml",
                OrganizationConfig.class);
        when(latestOrgConfig.getConfig()).thenReturn(mockOrgConfig);
        // Both projectA and projectB map to the same shared repository
        when(latestOrgConfig.projectNameToRepoFullName(any(), eq("projectA")))
                .thenReturn("test-org/project-shared");
        when(latestOrgConfig.projectNameToRepoFullName(any(), eq("projectB")))
                .thenReturn("test-org/project-shared");

        // Shared project config
        var sharedConfig = loadYamlResource(
                "src/test/resources/cf-haus-manager-shared-repo.yml",
                ProjectConfig.class);
        var sharedProjectState = new ProjectConfigState(
                ProjectManager.repoNametoTaskGroup("test-org/project-shared"),
                () -> {
                },
                "test-org/project-shared",
                home_project_1.installationId(),
                sharedConfig);
        when(latestProjectConfig.getProjectConfigState("test-org/project-shared"))
                .thenReturn(sharedProjectState);
        when(latestProjectConfig.getAllProjects())
                .thenReturn(List.of(sharedProjectState));

        // Setup: test-org-shared is assigned to projectA but not installed
        // This will trigger an error email
        // Note: NOT adding test-org-shared to installationMap to trigger "not installed" error

        // Execute
        installMonitor.checkInstallations(true);
        waitForQueue();

        // Verify: Email subject should use "shared" (from project-shared), not "projectB"
        // Bug: Currently uses "projectB" because it's last in YAML iteration order
        // Fix: Should extract "shared" from "project-shared" repository name
        var sharedErrors = mailbox.getMailsSentTo("errors@projectA.dev");
        assertThat(sharedErrors).hasSize(1);
        String subject = sharedErrors.get(0).getSubject();

        // After fix, this should pass:
        assertThat(subject).contains("shared");
        // Before fix, this would be true (the bug):
        // assertThat(subject).contains("projectB");
        assertThat(subject).doesNotContain("projectA");
        assertThat(subject).doesNotContain("projectB");
    }
}
