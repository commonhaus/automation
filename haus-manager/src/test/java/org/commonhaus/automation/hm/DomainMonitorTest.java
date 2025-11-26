package org.commonhaus.automation.hm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.commonhaus.automation.hm.ProjectManager.ProjectConfigState;
import org.commonhaus.automation.hm.config.LatestOrgConfig;
import org.commonhaus.automation.hm.config.LatestProjectConfig;
import org.commonhaus.automation.hm.config.ManagedDomain;
import org.commonhaus.automation.hm.config.OrganizationConfig;
import org.commonhaus.automation.hm.config.ProjectConfig;
import org.commonhaus.automation.hm.github.HausManagerTestBase;
import org.commonhaus.automation.hm.github.TestNamecheapConfig;
import org.commonhaus.automation.hm.namecheap.NamecheapService;
import org.commonhaus.automation.hm.namecheap.models.ContactInfo;
import org.commonhaus.automation.hm.namecheap.models.DomainContacts;
import org.commonhaus.automation.hm.namecheap.models.DomainRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.logging.Log;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class DomainMonitorTest extends HausManagerTestBase {

    @Inject
    DomainMonitor domainMonitor;

    @InjectMock
    NamecheapService namecheapService;

    @InjectMock
    LatestOrgConfig latestOrgConfig;

    @InjectMock
    LatestProjectConfig latestProjectConfig;

    private DomainContacts defaultContacts;

    private OrganizationConfig mockOrgConfig;

    MockInstallation home_project_1;
    private ProjectConfigState mockProjectState1;

    MockInstallation home_project_2;
    private ProjectConfigState mockProjectState2;

    private TestNamecheapConfig namecheapConfig;

    @BeforeEach
    @Override
    protected void setup() throws IOException {
        super.setup();
        home_project_1 = setupInstallationMocks(HOME_PROJECT_1);
        home_project_2 = setupInstallationMocks(HOME_PROJECT_2);

        Log.info("START: DomainManagerTest.setup()");

        defaultContacts = createTestContacts("Default", "Org");

        namecheapConfig = new TestNamecheapConfig(defaultContacts);
        configProducer.setNamecheapConfig(namecheapConfig);

        // Setup common mocks
        when(namecheapService.isEnabled()).thenReturn(true);
        when(namecheapService.defaultContacts()).thenReturn(defaultContacts);

        // Create mock org config
        mockOrgConfig = loadYamlResource(
                "src/test/resources/cf-haus-organization-domains.yml",
                OrganizationConfig.class);
        when(latestOrgConfig.getConfig()).thenReturn(mockOrgConfig);

        // Project 1 mock config/state
        var config = loadYamlResource(
                "src/test/resources/cf-haus-manager-project1.yml",
                ProjectConfig.class);

        var repo = HOME_PROJECT_1.repoFullName();
        mockProjectState1 = new ProjectConfigState(
                ProjectManager.repoNametoTaskGroup(repo),
                () -> {
                },
                repo,
                HOME_PROJECT_1.installId(),
                config);
        when(latestProjectConfig.getProjectConfigState(repo))
                .thenReturn(mockProjectState1);

        // Project 2 mock config/state
        config = loadYamlResource(
                "src/test/resources/cf-haus-manager-project2.yml",
                ProjectConfig.class);

        repo = HOME_PROJECT_2.repoFullName();
        mockProjectState2 = new ProjectConfigState(
                ProjectManager.repoNametoTaskGroup(repo),
                () -> {
                },
                repo,
                HOME_PROJECT_2.installId(),
                config);
        when(latestProjectConfig.getProjectConfigState(repo))
                .thenReturn(mockProjectState2);
        when(latestProjectConfig.getAllProjects())
                .thenReturn(List.of(mockProjectState1, mockProjectState2));
    }

    @Test
    void testValidDomainsContactSync() {
        Log.info("TEST: All valid domains (org-managed and project-managed) get contact sync");

        // Setup: Org-managed domain
        String orgDomain = "test.org";
        DomainRecord ncOrgDomain = createDomainRecord(orgDomain);
        DomainContacts orgCurrentContacts = createTestContacts("Current", "Org");

        // Setup: Project-managed domains
        String project1Domain = "test-project1.org";
        DomainRecord ncProject1Domain = createDomainRecord(project1Domain);
        DomainContacts project1CurrentContacts = createTestContacts("Current", "P1");

        String project2Domain = "test-project2.org";
        DomainRecord ncProject2Domain = createDomainRecord(project2Domain);
        DomainContacts project2CurrentContacts = createTestContacts("Current", "P2");

        // Mock Namecheap to return all domains
        when(namecheapService.fetchAllDomains()).thenReturn(List.of(
                ncOrgDomain, ncProject1Domain, ncProject2Domain));
        when(namecheapService.getContacts(orgDomain)).thenReturn(Optional.of(orgCurrentContacts));
        when(namecheapService.getContacts(project1Domain)).thenReturn(Optional.of(project1CurrentContacts));
        when(namecheapService.getContacts(project2Domain)).thenReturn(Optional.of(project2CurrentContacts));
        when(namecheapService.setContacts(eq(orgDomain), any())).thenReturn(true);
        when(namecheapService.setContacts(eq(project1Domain), any())).thenReturn(true);
        when(namecheapService.setContacts(eq(project2Domain), any())).thenReturn(true);

        // Org manages test.org directly
        var domainList = mockOrgConfig.domainManagement().domains();
        domainList.clear();
        domainList.add(new ManagedDomain(orgDomain));

        // Projects and their domain assignments come from setup
        // Project one maintains test-project1.org
        // Project two maintains test-project2.org
        // Org config assigns domains to respective projects

        // Execute
        domainMonitor.refreshDomains(true);
        waitForQueue();

        // Verify: All valid domains should have contacts synced
        verify(namecheapService, times(1)).setContacts(eq(orgDomain), any());
        verify(namecheapService, times(1)).setContacts(eq(project1Domain), any());
        verify(namecheapService, times(1)).setContacts(eq(project2Domain), any());

        // Verify: Audit email sent to org with summary of valid domains
        var auditEmails = mailbox.getMailsSentTo("audit@test.org");
        assertThat(auditEmails).hasSize(2);
        assertThat(auditEmails.get(0).getSubject()).contains("Domain reconciliation summary");
        assertThat(auditEmails.get(0).getText()).contains(orgDomain);
        assertThat(auditEmails.get(0).getText()).contains(project1Domain);
        assertThat(auditEmails.get(0).getText()).contains(project2Domain);

        assertThat(auditEmails.get(1).getSubject()).contains("Domain contacts updated");
        assertThat(auditEmails.get(1).getText()).contains(orgDomain);
        assertThat(auditEmails.get(1).getText()).doesNotContain(project1Domain);
        assertThat(auditEmails.get(1).getText()).doesNotContain(project2Domain);

        var projectEmails = mailbox.getMailsSentTo("audit@project2.dev");
        assertThat(projectEmails.get(0).getSubject()).contains("Domain contacts updated");
        assertThat(projectEmails.get(0).getText()).doesNotContain(orgDomain);
        assertThat(projectEmails.get(0).getText()).doesNotContain(project1Domain);
        assertThat(projectEmails.get(0).getText()).contains(project2Domain);
    }

    @Test
    void testOrgProjectConflict() {
        Log.info("TEST: Org manages domain but project also claims it - conflict detected");

        String domain = "conflict.com";
        ManagedDomain managedDomain = new ManagedDomain(domain);

        // Setup: Domain in both org and project configs
        DomainRecord ncDomain = createDomainRecord(domain);
        DomainContacts currentContacts = createTestContacts("Current", "Tech");

        when(namecheapService.fetchAllDomains()).thenReturn(List.of(ncDomain));
        when(namecheapService.getContacts(domain)).thenReturn(Optional.of(currentContacts));

        // Org manages directly
        mockOrgConfig.domainManagement().domains().add(managedDomain);

        // But project-one also claims it
        mockProjectState1.projectConfig().domainManagement().domains().add(managedDomain);

        // Execute
        domainMonitor.refreshDomains(true);
        waitForQueue();

        // Verify: Should NOT sync contacts due to conflict
        verify(namecheapService, never()).setContacts(eq(domain), any());

        // Verify: Error email sent to org about the conflict
        var orgErrorEmails = mailbox.getMailsSentTo("errors@test.org");
        assertThat(orgErrorEmails).hasSize(2);
        assertThat(orgErrorEmails.get(0).getSubject()).contains("Domain conflict");
        assertThat(orgErrorEmails.get(0).getText()).contains(domain);
        assertThat(orgErrorEmails.get(0).getText()).contains("also declared in project configuration");

        // Missing: Domains (default setup) are in config but not registered/mocked
        assertThat(orgErrorEmails.get(1).getSubject()).contains("Domains in configuration but not registered");
        assertThat(orgErrorEmails.get(1).getText()).contains("test.org");
        assertThat(orgErrorEmails.get(1).getText()).contains("not found in NameCheap");

        // Verify: Error email sent to project claiming the domain
        var projectErrorEmails = mailbox.getMailsSentTo("errors@project1.dev");
        assertThat(projectErrorEmails).hasSize(1);
        assertThat(projectErrorEmails.get(0).getSubject()).contains("Domain conflict");
        assertThat(projectErrorEmails.get(0).getText()).contains(domain);
        assertThat(projectErrorEmails.get(0).getText()).contains("owned and managed by the parent foundation");
    }

    @Test
    void testMultipleProjectsClaimSameDomain() {
        Log.info("TEST: Multiple projects claim same domain - conflict detected");

        String domain = "shared.com";
        ManagedDomain managedDomain = new ManagedDomain(domain);

        // Setup: Two projects claim the same domain
        DomainRecord ncDomain = createDomainRecord(domain);
        DomainContacts currentContacts = createTestContacts("Current", "Tech");

        when(namecheapService.fetchAllDomains()).thenReturn(List.of(ncDomain));
        when(namecheapService.getContacts(domain)).thenReturn(Optional.of(currentContacts));

        // clear org domains for this test
        mockOrgConfig.domainManagement().domains().clear();

        // Org assigns to project-one
        var assets = mockOrgConfig.projects().assetsForProject("one");
        assets.domainAssociation().clear();
        assets.domainAssociation().add(domain);

        // Project one and Project two claim it
        mockProjectState1.projectConfig().domainManagement().domains().clear();
        mockProjectState1.projectConfig().domainManagement().domains().add(managedDomain);
        mockProjectState2.projectConfig().domainManagement().domains().clear();
        mockProjectState2.projectConfig().domainManagement().domains().add(managedDomain);

        // Execute
        domainMonitor.refreshDomains(true);
        waitForQueue();

        // Verify: Should NOT sync contacts due to conflict
        verify(namecheapService, never()).setContacts(eq(domain), any());

        // Verify: Error emails sent to both projects claiming the domain
        var project1Emails = mailbox.getMailsSentTo("errors@project1.dev");
        assertThat(project1Emails).hasSize(1);
        assertThat(project1Emails.get(0).getSubject()).contains("Domain conflict");
        assertThat(project1Emails.get(0).getText()).contains(domain);
        assertThat(project1Emails.get(0).getText()).contains("declared in multiple project configurations");
        assertThat(project1Emails.get(0).getText()).contains("haus-manager configuration in test-org/project-one");

        var project2Emails = mailbox.getMailsSentTo("errors@project2.dev");
        assertThat(project2Emails).hasSize(2);
        assertThat(project2Emails.get(0).getSubject()).contains("Domain conflict");
        assertThat(project2Emails.get(0).getText()).contains(domain);
        assertThat(project2Emails.get(0).getText()).contains("declared in multiple project configurations");
        assertThat(project2Emails.get(0).getText()).contains("haus-manager configuration in test-org/project-two");

        assertThat(project2Emails.get(1).getSubject()).contains("Domain assignment mismatch");
        assertThat(project2Emails.get(1).getText()).contains("test-project2.org");
        assertThat(project2Emails.get(1).getText()).contains("haus-manager configuration in test-org/project-two");

        var orgEmails = mailbox.getMailsSentTo("errors@test.org");
        assertThat(orgEmails).hasSize(2);
        assertThat(orgEmails.get(0).getSubject()).contains("Domain conflict");
        assertThat(orgEmails.get(0).getText()).contains(domain);
        assertThat(orgEmails.get(0).getText()).contains("declared in multiple project configurations");
        assertThat(orgEmails.get(0).getText()).contains("Projects have been notified");

        assertThat(orgEmails.get(1).getSubject()).contains("Domain assignment mismatch");
        assertThat(orgEmails.get(1).getText()).contains("test-project2.org");
        assertThat(orgEmails.get(1).getText()).contains("has assignment mismatches");
        assertThat(orgEmails.get(1).getText()).contains("Projects have been notified");
    }

    @Test
    void testOrphanDomain() {
        Log.info("TEST: Domain in Namecheap but not in any config - orphan detected");

        String domain = "orphan.com";

        // Setup: Domain exists in Namecheap but no config references it
        DomainRecord ncDomain = createDomainRecord(domain);
        DomainContacts currentContacts = createTestContacts("Current", "Tech");

        when(namecheapService.fetchAllDomains()).thenReturn(List.of(ncDomain));
        when(namecheapService.getContacts(domain)).thenReturn(Optional.of(currentContacts));

        // Clear org domains so orphan.com is truly orphaned
        mockOrgConfig.domainManagement().domains().clear();
        mockOrgConfig.projects().allAssets().clear();
        mockProjectState1.projectConfig().domainManagement().domains().clear();
        mockProjectState2.projectConfig().domainManagement().domains().clear();

        // Execute
        domainMonitor.refreshDomains(true);
        waitForQueue();

        // Verify: Should NOT sync contacts for orphan domain
        verify(namecheapService, never()).setContacts(eq(domain), any());

        // Verify: Org receives error notification about unconfigured domain
        var orgErrorEmails = mailbox.getMailsSentTo("errors@test.org");
        assertThat(orgErrorEmails).hasSize(1);
        assertThat(orgErrorEmails.get(0).getSubject()).contains("Unconfigured domains in NameCheap");
        assertThat(orgErrorEmails.get(0).getText()).contains(domain);
        assertThat(orgErrorEmails.get(0).getText()).contains("not present in any organization or project configuration");
    }

    @Test
    void testContactSyncOnlyWhenNeeded() {
        Log.info("TEST: Contact sync skipped when contacts already match");

        String orgDomain = "test.org";
        DomainRecord ncOrgDomain = createDomainRecord(orgDomain);

        String project1Domain = "test-project1.org";
        DomainRecord ncProject1Domain = createDomainRecord(project1Domain);

        String project2Domain = "test-project2.org";
        DomainRecord ncProject2Domain = createDomainRecord(project2Domain);

        // Setup: Contacts match exactly
        when(namecheapService.fetchAllDomains()).thenReturn(List.of(
                ncOrgDomain, ncProject1Domain, ncProject2Domain));
        when(namecheapService.getContacts(orgDomain)).thenReturn(Optional.of(defaultContacts));
        when(namecheapService.getContacts(project1Domain)).thenReturn(Optional.of(defaultContacts));
        when(namecheapService.getContacts(project2Domain)).thenReturn(Optional.of(defaultContacts));

        // Use project/org configs from setup where domains are defined
        // Execute
        domainMonitor.refreshDomains(true);
        waitForQueue();

        // Verify: Should NOT call setContacts since they already match
        verify(namecheapService, never()).setContacts(eq(orgDomain), any());
        verify(namecheapService, never()).setContacts(eq(project1Domain), any());
        verify(namecheapService, never()).setContacts(eq(project2Domain), any());
    }

    // ========== Helper Methods ==========

    private DomainRecord createDomainRecord(String domainName) {
        return new DomainRecord(domainName, LocalDate.parse("2025-12-31"),
                false, false, true, false);
    }

    private DomainContacts createTestContacts(String firstName, String lastName) {
        ContactInfo contact = new ContactInfo(
                firstName, lastName,
                "123 Test St", "Test City", "CA", "12345", "US",
                "+1.5555551234", "test@example.com",
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), false);
        return new DomainContacts(contact, contact, contact, contact);
    }
}
