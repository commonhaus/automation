package org.commonhaus.automation.hm;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import org.commonhaus.automation.hm.AnnualAssetReport.AssetMismatch;
import org.commonhaus.automation.hm.AnnualAssetReport.AssetType;
import org.commonhaus.automation.hm.AnnualAssetReport.MismatchType;
import org.commonhaus.automation.hm.AnnualAssetReport.ProjectAssetReconciliation;
import org.commonhaus.automation.hm.ProjectManager.ProjectConfigState;
import org.commonhaus.automation.hm.config.LatestOrgConfig;
import org.commonhaus.automation.hm.config.LatestProjectConfig;
import org.commonhaus.automation.hm.config.OrganizationConfig;
import org.commonhaus.automation.hm.config.ProjectConfig;
import org.commonhaus.automation.hm.github.HausManagerTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class AnnualAssetReportTest extends HausManagerTestBase {

    @Inject
    AnnualAssetReport report;

    @InjectMock
    LatestOrgConfig latestOrgConfig;

    @InjectMock
    LatestProjectConfig latestProjectConfig;

    @BeforeEach
    @Override
    protected void setup() throws IOException {
        super.setup();
    }

    private ProjectConfigState projectState(String yamlResource) throws IOException {
        ProjectConfig projectConfig = loadYamlResource(
                "src/test/resources/" + yamlResource, ProjectConfig.class);
        return new ProjectConfigState(
                ProjectManager.repoNametoTaskGroup(PROJECT_ORG.repoFullName()),
                () -> {
                },
                PROJECT_ORG.repoFullName(), PROJECT_ORG.installId(), projectConfig);
    }

    // -- reconcileProjectAssets: direct, no mocking --------------------------

    // annual-report-one-domain.yml declares one domain ("declared.example") and nothing else.
    // The next two tests reuse it with different orgExpectedDomains maps — matched vs.
    // project-only is entirely a property of what the org expects, not of the fixture itself.

    @Test
    void testMatchedDomain() throws IOException {
        ProjectConfigState state = projectState("annual-report-one-domain.yml");
        Map<String, Set<String>> orgExpectedDomains = Map.of("declared.example", Set.of("proj"));

        when(latestOrgConfig.projectNameToRepoFullName(any(), eq("proj")))
                .thenReturn(PROJECT_ORG.repoFullName());

        ProjectAssetReconciliation recon = report.reconcileProjectAssets(state, orgExpectedDomains, Map.of());

        assertThat(recon.matchedDomains()).containsExactly("declared.example");
        assertThat(recon.mismatches()).isEmpty();
    }

    @Test
    void testProjectOnlyDomain() throws IOException {
        // Same fixture as testMatchedDomain, but org config doesn't claim "declared.example"
        // for any project this time, so it's project-only instead of matched.
        ProjectConfigState state = projectState("annual-report-one-domain.yml");

        ProjectAssetReconciliation recon = report.reconcileProjectAssets(state, Map.of(), Map.of());

        assertThat(recon.matchedDomains()).isEmpty();
        assertThat(recon.mismatches()).containsExactly(
                new AssetMismatch(AssetType.DOMAIN, "declared.example", MismatchType.IN_PROJECT_NOT_IN_ORG,
                        "Declared in project but not registered with foundation"));
    }

    @Test
    void testOrgOnlyDomain() throws IOException {
        ProjectConfigState state = projectState("annual-report-empty.yml");
        Map<String, Set<String>> orgExpectedDomains = Map.of("org-only.example", Set.of("proj"));

        when(latestOrgConfig.projectNameToRepoFullName(any(), eq("proj")))
                .thenReturn(PROJECT_ORG.repoFullName());

        ProjectAssetReconciliation recon = report.reconcileProjectAssets(state, orgExpectedDomains, Map.of());

        assertThat(recon.matchedDomains()).isEmpty();
        assertThat(recon.mismatches()).containsExactly(
                new AssetMismatch(AssetType.DOMAIN, "org-only.example", MismatchType.IN_ORG_NOT_IN_PROJECT,
                        "Expected by foundation but not declared in project"));
    }

    // annual-report-one-org.yml declares one GitHub org ("declared-org") and nothing else.
    // Same reuse pattern as annual-report-one-domain.yml above: matched vs. project-only comes
    // from the orgExpectedOrgs map each test supplies, not from the fixture.

    @Test
    void testMatchedGithubOrg() throws IOException {
        ProjectConfigState state = projectState("annual-report-one-org.yml");
        Map<String, Set<String>> orgExpectedOrgs = Map.of("https://github.com/declared-org", Set.of("proj"));

        when(latestOrgConfig.projectNameToRepoFullName(any(), eq("proj")))
                .thenReturn(PROJECT_ORG.repoFullName());

        ProjectAssetReconciliation recon = report.reconcileProjectAssets(state, Map.of(), orgExpectedOrgs);

        assertThat(recon.matchedGithubOrgs()).containsExactly("https://github.com/declared-org");
        assertThat(recon.mismatches()).isEmpty();
    }

    @Test
    void testProjectOnlyGithubOrg() throws IOException {
        // Same fixture as testMatchedGithubOrg, but org config doesn't claim "declared-org"
        // for any project this time, so it's project-only instead of matched.
        ProjectConfigState state = projectState("annual-report-one-org.yml");

        ProjectAssetReconciliation recon = report.reconcileProjectAssets(state, Map.of(), Map.of());

        assertThat(recon.matchedGithubOrgs()).isEmpty();
        assertThat(recon.mismatches()).containsExactly(
                new AssetMismatch(AssetType.GITHUB_ORG, "https://github.com/declared-org",
                        MismatchType.IN_PROJECT_NOT_IN_ORG,
                        "Declared in project but not registered with foundation"));
    }

    @Test
    void testOrgOnlyGithubOrg() throws IOException {
        ProjectConfigState state = projectState("annual-report-empty.yml");
        Map<String, Set<String>> orgExpectedOrgs = Map.of("https://github.com/org-only", Set.of("proj"));

        when(latestOrgConfig.projectNameToRepoFullName(any(), eq("proj")))
                .thenReturn(PROJECT_ORG.repoFullName());

        ProjectAssetReconciliation recon = report.reconcileProjectAssets(state, Map.of(), orgExpectedOrgs);

        assertThat(recon.matchedGithubOrgs()).isEmpty();
        assertThat(recon.mismatches()).containsExactly(
                new AssetMismatch(AssetType.GITHUB_ORG, "https://github.com/org-only",
                        MismatchType.IN_ORG_NOT_IN_PROJECT,
                        "Expected by foundation but not declared in project"));
    }

    /**
     * Models the real "pseudo-project group" shape in cf-haus-organization.yml (e.g. the
     * easymock/objenesis pair, or the larger wildfly group): multiple org-config pseudo-project
     * entries (projectA, projectB) share the same projectRepository (project-shared), each
     * declaring its own domain. Reuses the real cf-haus-organization-shared-repo.yml /
     * cf-haus-manager-shared-repo.yml fixture pair already established by
     * InstallMonitorTest#testSharedRepositoryProjectName, rather than inventing new fixtures.
     * The shared project config declares projectA's GitHub org and projectA's domain, but not
     * projectB's domain — proving the pseudo-project fan-in correctly resolves both a match
     * (projecta.example.org, via the projectA entry) and a mismatch (projectb.example.org, via
     * the projectB entry) within the same asset cluster.
     */
    @Test
    void testProjectGroupMultiple() throws IOException {
        var orgConfig = loadYamlResource(
                "src/test/resources/cf-haus-organization-shared-repo.yml",
                OrganizationConfig.class);
        when(latestOrgConfig.projectNameToRepoFullName(any(), eq("projectA")))
                .thenReturn("test-org/project-shared");
        when(latestOrgConfig.projectNameToRepoFullName(any(), eq("projectB")))
                .thenReturn("test-org/project-shared");

        ProjectConfig sharedConfig = loadYamlResource(
                "src/test/resources/cf-haus-manager-shared-repo.yml", ProjectConfig.class);
        ProjectConfigState state = new ProjectConfigState(
                ProjectManager.repoNametoTaskGroup("test-org/project-shared"),
                () -> {
                },
                "test-org/project-shared", PROJECT_ORG.installId(), sharedConfig);

        Map<String, Set<String>> orgExpectedDomains = orgConfig.projects().expectedDomains();
        Map<String, Set<String>> orgExpectedOrgs = orgConfig.projects().expectedOrganizations();

        ProjectAssetReconciliation recon = report.reconcileProjectAssets(state, orgExpectedDomains, orgExpectedOrgs);

        assertThat(recon.matchedDomains()).containsExactly("projecta.example.org");
        assertThat(recon.matchedGithubOrgs()).containsExactly("https://github.com/test-org-shared");
        assertThat(recon.mismatches()).containsExactly(
                new AssetMismatch(AssetType.DOMAIN, "projectb.example.org", MismatchType.IN_ORG_NOT_IN_PROJECT,
                        "Expected by foundation but not declared in project"));
    }

    // -- sendAnnualReport: mail routing ---------------------------------------

    private ProjectAssetReconciliation emptyRecon(ProjectConfigState state) {
        // Empty but mutable lists — sendAnnualReport sorts matchedDomains/matchedGithubOrgs/
        // mismatches in place; List.of() would throw UnsupportedOperationException there.
        return new ProjectAssetReconciliation(
                "proj", state.repoFullName(), state,
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    @Test
    void testSendReportAuditAddressConfigured() throws IOException {
        // cf-haus-manager-shared-repo.yml has emailNotifications.audit = audit@projectA.dev
        ProjectConfigState state = projectState("cf-haus-manager-shared-repo.yml");
        var orgConfig = loadYamlResource(
                "src/test/resources/cf-haus-organization-shared-repo.yml", OrganizationConfig.class);
        when(latestOrgConfig.getConfig()).thenReturn(orgConfig);

        report.sendAnnualReport(emptyRecon(state), false);

        // sendAnnualReport fires mail via the EventBus (fire-and-forget), not through
        // updateQueue, so there's nothing for waitForQueue() to wait on — await() directly on
        // the mailbox instead, same reason TeamConflictResolverTest relies on waitForQueue()
        // before its own mailbox assertions.
        await().atMost(5, SECONDS).untilAsserted(() -> {
            assertThat(mailbox.getMailsSentTo("audit@projectA.dev")).hasSize(1);
            // Foundation audit CC, from the org config's own emailNotifications.audit
            assertThat(mailbox.getMailsSentTo("audit@test.org")).hasSize(1);
        });
    }

    @Test
    void testSendReportAuditAddressEmptyFallsBackToErrors() throws IOException {
        // project-1-config.yml has only emailNotifications.errors, no audit
        ProjectConfigState state = projectState("project-1-config.yml");
        var orgConfig = loadYamlResource(
                "src/test/resources/cf-haus-organization-shared-repo.yml", OrganizationConfig.class);
        when(latestOrgConfig.getConfig()).thenReturn(orgConfig);

        report.sendAnnualReport(emptyRecon(state), false);

        await().atMost(5, SECONDS).untilAsserted(
                () -> assertThat(mailbox.getMailsSentTo("test@commonhaus.org")).hasSize(1));
    }

    @Test
    void testSendReportDryRun() throws IOException {
        // cf-haus-manager-shared-repo.yml has emailNotifications.audit = audit@projectA.dev
        ProjectConfigState state = projectState("cf-haus-manager-shared-repo.yml");
        var orgConfig = loadYamlResource(
                "src/test/resources/cf-haus-organization-shared-repo.yml", OrganizationConfig.class);
        when(latestOrgConfig.getConfig()).thenReturn(orgConfig);

        report.sendAnnualReport(emptyRecon(state), true);

        // Dry run: the CC still goes out (routed to the org's dryRun address instead of
        // audit) — wait for that first, so the "no mail" assertions below aren't just
        // checking an empty mailbox before the async send has had a chance to land.
        await().atMost(5, SECONDS).untilAsserted(
                () -> assertThat(mailbox.getMailsSentTo("test@test.org")).hasSize(1));

        // No mail to the project's own audit address, and no CC to the (non-dry-run) audit
        // address either.
        assertThat(mailbox.getMailsSentTo("audit@projectA.dev")).isEmpty();
        assertThat(mailbox.getMailsSentTo("audit@test.org")).isEmpty();
    }

    // -- generateAnnualReports: orchestration edges ---------------------------
    //
    // "One project's sendAnnualReport throws, others still processed" was considered and
    // dropped: AnnualAssetReport does no live I/O in its call graph (config is in-memory,
    // mail send is fire-and-forget), so there's no realistic input that makes sendAnnualReport
    // throw for one project without a Mockito.spy() — a pattern with no precedent elsewhere in
    // this suite. See spec.md's coverage table for the full reasoning.

    @Test
    void testHomeRepositorySkipped() throws IOException {
        // PRIMARY.repoFullName() ("test-org/test-repo") is also TestManagerBotConfig.DEFAULT's
        // home repo, so a project fixture using that same repoFullName should be skipped.
        var orgConfig = loadYamlResource(
                "src/test/resources/cf-haus-organization-shared-repo.yml", OrganizationConfig.class);
        when(latestOrgConfig.getConfig()).thenReturn(orgConfig);

        ProjectConfig homeConfig = loadYamlResource(
                "src/test/resources/cf-haus-manager-shared-repo.yml", ProjectConfig.class);
        ProjectConfigState homeState = new ProjectConfigState(
                ProjectManager.repoNametoTaskGroup(PRIMARY.repoFullName()),
                () -> {
                },
                PRIMARY.repoFullName(), PRIMARY.installId(), homeConfig);
        when(latestProjectConfig.getAllProjects()).thenReturn(List.of(homeState));

        report.generateAnnualReports(false);

        await().during(2, SECONDS).atMost(2, SECONDS)
                .failFast(() -> !mailbox.getMailsSentTo("audit@projectA.dev").isEmpty())
                .untilAsserted(
                        () -> assertThat(mailbox.getMailsSentTo("audit@projectA.dev")).isEmpty());
    }

    @Test
    void testNoProjects() throws IOException {
        var orgConfig = loadYamlResource(
                "src/test/resources/cf-haus-organization-shared-repo.yml", OrganizationConfig.class);
        when(latestOrgConfig.getConfig()).thenReturn(orgConfig);
        when(latestProjectConfig.getAllProjects()).thenReturn(List.of());

        report.generateAnnualReports(false);

        // No exception propagates (JUnit would fail the test if it did). Negative assertion on
        // mail, same reasoning as testHomeRepositorySkipped: wait long enough for a wrongful
        // send to have arrived before confirming it didn't.
        await().during(2, SECONDS).atMost(2, SECONDS)
                .untilAsserted(() -> {
                    assertThat(mailbox.getMailsSentTo("audit@test.org")).isEmpty();
                    assertThat(mailbox.getMailsSentTo("errors@test.org")).isEmpty();
                });
    }
}
