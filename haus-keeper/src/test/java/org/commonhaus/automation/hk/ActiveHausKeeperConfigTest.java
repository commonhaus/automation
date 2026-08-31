package org.commonhaus.automation.hk;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.inject.Inject;

import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.github.watchers.FileWatcher;
import org.commonhaus.automation.hk.config.HausKeeperConfig;
import org.commonhaus.automation.hk.github.HausKeeperTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class ActiveHausKeeperConfigTest extends HausKeeperTestBase {

    static final String ORG_CONFIG_REPO = "commonhaus/foundation";
    static final String ORG_CONFIG_PATH = "cf-haus-organization.yml";
    static final String ORG_CONFIG_TASK_GROUP = "haus-keeper-org-config";

    @Inject
    TestUserManagementConfig testConfig;

    @Inject
    FileWatcher fileWatcher;

    @Inject
    TestFileWatcher testFileWatcher;

    ScopedQueryContext qc;

    @BeforeEach
    void setupOrgConfigTest() throws Exception {
        // organizationDomains, the FileWatcher's registered watches, and
        // domainChangeCallbacks entries registered under a test-specific key
        // are all singleton CDI bean state shared across every test class in
        // the same JVM/build, so state from a prior test/class must be
        // cleared first.
        testConfig.testResetOrganizationDomains();
        testFileWatcher.testReset();
        setupInstallationRepositories();
        qc = new ScopedQueryContext(ctx, hausMocks.installationId(), hausMocks.repository());
    }

    HausKeeperConfig loadConfig(String fixtureFileName) throws Exception {
        return ctx.yamlMapper().readValue(
                ActiveHausKeeperConfigTest.class.getResourceAsStream("/" + fixtureFileName), HausKeeperConfig.class);
    }

    void mockOrgConfig(String fixtureFileName) throws Exception {
        mockFileContent(hausMocks.repository(), ORG_CONFIG_PATH,
                Path.of("src/test/resources/" + fixtureFileName));
    }

    @Test
    void testGetDomainsForProjectPopulatesCacheOnUpdate() throws Exception {
        mockOrgConfig("cf-haus-organization.yml");
        HausKeeperConfig config = loadConfig("cf-haus-keeper-org-source.yml");

        testConfig.testUpdate(qc, config);
        drainQueue(updateQueue, 5);

        assertThat(testConfig.getDomainsForProject("foo")).containsExactly("foo.org");
        assertThat(testConfig.getDomainsForProject("bar")).containsExactly("bar.org");
        assertThat(testConfig.getDomainsForProject("unknown")).isEmpty();
    }

    @Test
    void testAbsentOrganizationConfigIsSilentNoOp() throws Exception {
        HausKeeperConfig config = loadConfig("cf-haus-keeper.yml");

        testConfig.testUpdate(qc, config);
        drainQueue(updateQueue, 5);

        assertThat(testConfig.getDomainsForProject("foo")).isEmpty();
        assertThat(fileWatcher.isWatching(ORG_CONFIG_REPO)).isFalse();
    }

    @Test
    void testWatcherRegisteredOnFirstUpdate() throws Exception {
        mockOrgConfig("cf-haus-organization.yml");
        HausKeeperConfig config = loadConfig("cf-haus-keeper-org-source.yml");

        testConfig.testUpdate(qc, config);
        drainQueue(updateQueue, 5);

        assertThat(fileWatcher.isWatching(ORG_CONFIG_REPO)).isTrue();
    }

    @Test
    void testOrgConfigReadFromDifferentInstallationThanHome() throws Exception {
        // organizationConfig points at the sponsors org/installation here, which is
        // neither home (hausMocks) nor datastore (dataMocks) -- proving that the
        // installation actually used to read/watch the file follows the RepoSource,
        // rather than happening to work because it's the same installation as home.
        String crossInstallRepo = "commonhaus-test/sponsors-test";
        mockFileContent(sponsorMocks.repository(), "cf-haus-organization.yml",
                Path.of("src/test/resources/cf-haus-organization.yml"));
        HausKeeperConfig config = loadConfig("cf-haus-keeper-org-source-cross-install.yml");

        testConfig.testUpdate(qc, config);
        drainQueue(updateQueue, 5);

        assertThat(fileWatcher.isWatching(crossInstallRepo)).isTrue();
        assertThat(testConfig.getDomainsForProject("foo")).containsExactly("foo.org");
    }

    @Test
    void testWatcherReRegisteredWhenRepoSourceChanges() throws Exception {
        mockOrgConfig("cf-haus-organization.yml");
        HausKeeperConfig config = loadConfig("cf-haus-keeper-org-source.yml");
        testConfig.testUpdate(qc, config);
        drainQueue(updateQueue, 5);
        assertThat(fileWatcher.isWatching(ORG_CONFIG_REPO)).isTrue();

        // Change the RepoSource to a new file path in the same repo
        mockFileContent(hausMocks.repository(), "cf-haus-organization-alt.yml",
                Path.of("src/test/resources/cf-haus-organization-alt.yml"));
        HausKeeperConfig config2 = loadConfig("cf-haus-keeper-org-source-alt.yml");
        testConfig.testUpdate(qc, config2);
        drainQueue(updateQueue, 5);

        // New path is watched and read
        assertThat(testConfig.getDomainsForProject("foo")).containsExactly("foo.alt");

        // Directly prove the OLD path is no longer watched: make it fail to read,
        // then force a refresh of every registered watch under the task group. If
        // the old path's watch were still registered, this would trigger a read
        // failure and an alert email; since it's been unwatched, refresh() never
        // touches it, so no alert fires and the cache stays as the new/alt data.
        mailbox.clear();
        org.mockito.Mockito.doReturn(null).when(hausMocks.repository()).getFileContent(ORG_CONFIG_PATH);
        fileWatcher.refresh(ctx, ORG_CONFIG_TASK_GROUP);
        drainQueue(updateQueue, 5);

        assertThat(mailbox.getTotalMessagesSent()).isZero();
        assertThat(testConfig.getDomainsForProject("foo")).containsExactly("foo.alt");
    }

    @Test
    void testWatcherNotDuplicatedWhenRepoSourceUnchanged() throws Exception {
        mockOrgConfig("cf-haus-organization.yml");
        HausKeeperConfig config = loadConfig("cf-haus-keeper-org-source.yml");

        testConfig.testUpdate(qc, config);
        drainQueue(updateQueue, 5);
        testConfig.testUpdate(qc, config);
        drainQueue(updateQueue, 5);

        assertThat(testConfig.getDomainsForProject("foo")).containsExactly("foo.org");
        assertThat(fileWatcher.isWatching(ORG_CONFIG_REPO)).isTrue();

        // Directly rule out duplicate registration. FileWatcher.refresh() dispatches
        // one CHANGE task per registered TaskCallback for the path/taskGroup, and
        // (unlike RECONCILE tasks) CHANGE tasks are never collapsed -- every queued
        // one runs. Each dispatched callback synchronously reads the file via
        // repo.getFileContent(...) before anything is queued for reconciliation, so
        // counting that mock invocation directly reveals how many callbacks actually
        // fired -- unlike asserting on the batched domain-change notification, which
        // would be silently collapsed by the RECONCILE queue if it fired twice for
        // the same registered id and so cannot distinguish "one callback" from "two
        // callbacks, deduplicated downstream."
        org.mockito.Mockito.clearInvocations(hausMocks.repository());
        fileWatcher.refresh(ctx, ORG_CONFIG_TASK_GROUP);
        drainQueue(updateQueue, 5);

        org.mockito.Mockito.verify(hausMocks.repository(), org.mockito.Mockito.times(1))
                .getFileContent(ORG_CONFIG_PATH);
    }

    @Test
    void testWatcherUnregisteredWhenRepoSourceBecomesEmpty() throws Exception {
        mockOrgConfig("cf-haus-organization.yml");
        HausKeeperConfig config = loadConfig("cf-haus-keeper-org-source.yml");
        testConfig.testUpdate(qc, config);
        drainQueue(updateQueue, 5);
        assertThat(fileWatcher.isWatching(ORG_CONFIG_REPO)).isTrue();

        HausKeeperConfig emptyConfig = loadConfig("cf-haus-keeper.yml");
        testConfig.testUpdate(qc, emptyConfig);
        drainQueue(updateQueue, 5);

        assertThat(fileWatcher.isWatching(ORG_CONFIG_REPO)).isFalse();
        // Cache from before is untouched by simply removing the RepoSource
        assertThat(testConfig.getDomainsForProject("foo")).containsExactly("foo.org");
    }

    @Test
    void testWatcherTornDownWhenUserManagementBecomesDisabled() throws Exception {
        mockOrgConfig("cf-haus-organization.yml");
        HausKeeperConfig config = loadConfig("cf-haus-keeper-org-source.yml");
        testConfig.testUpdate(qc, config);
        drainQueue(updateQueue, 5);
        assertThat(fileWatcher.isWatching(ORG_CONFIG_REPO)).isTrue();

        // Disable user management entirely (organizationConfig is still present in the YAML,
        // but enabled:false must take precedence and tear down the existing watch)
        String yaml = """
                userManagement:
                  enabled: false
                organizationConfig:
                  repository: %s
                  filePath: %s
                emailNotifications:
                  errors:
                    - repo-errors@example.com
                """.formatted(ORG_CONFIG_REPO, ORG_CONFIG_PATH);
        HausKeeperConfig disabledConfig = ctx.yamlMapper().readValue(yaml, HausKeeperConfig.class);

        testConfig.testUpdate(qc, disabledConfig);
        drainQueue(updateQueue, 5);

        assertThat(fileWatcher.isWatching(ORG_CONFIG_REPO)).isFalse();
        // Cache from before is untouched by disabling; fail-open leaves last-known-good data
        assertThat(testConfig.getDomainsForProject("foo")).containsExactly("foo.org");
    }

    @Test
    void testNoChangeCallbackDoesNotFireOnUnrelatedFieldEdit() throws Exception {
        mockOrgConfig("cf-haus-organization.yml");
        HausKeeperConfig config = loadConfig("cf-haus-keeper-org-source.yml");
        testConfig.testUpdate(qc, config);
        drainQueue(updateQueue, 5);

        Set<Set<String>> notifications = ConcurrentHashMap.newKeySet();
        testConfig.notifyOnDomainChange("test-no-change", notifications::add);

        // Re-mock same repo/path with content that changes an unrelated field only
        mockOrgConfig("cf-haus-organization-unrelated-change.yml");
        fileWatcher.refresh(ctx, ORG_CONFIG_TASK_GROUP);
        drainQueue(updateQueue, 5);

        assertThat(notifications).isEmpty();
        assertThat(testConfig.getDomainsForProject("foo")).containsExactly("foo.org");
    }

    @Test
    void testCallbackFiresWithExactlyOneChangedProjectName() throws Exception {
        mockOrgConfig("cf-haus-organization.yml");
        HausKeeperConfig config = loadConfig("cf-haus-keeper-org-source.yml");
        testConfig.testUpdate(qc, config);
        drainQueue(updateQueue, 5);

        Set<Set<String>> notifications = ConcurrentHashMap.newKeySet();
        testConfig.notifyOnDomainChange("test-one-change", notifications::add);

        mockOrgConfig("cf-haus-organization-one-changed.yml");
        fileWatcher.refresh(ctx, ORG_CONFIG_TASK_GROUP);
        drainQueue(updateQueue, 5);

        assertThat(notifications).hasSize(1);
        assertThat(notifications.iterator().next()).containsExactly("foo");
        assertThat(testConfig.getDomainsForProject("foo")).containsExactlyInAnyOrder("foo.org", "foo.com");
        assertThat(testConfig.getDomainsForProject("bar")).containsExactly("bar.org");
    }

    @Test
    void testCallbackFiresOnceWithAllChangedProjectNamesBatched() throws Exception {
        mockOrgConfig("cf-haus-organization.yml");
        HausKeeperConfig config = loadConfig("cf-haus-keeper-org-source.yml");
        testConfig.testUpdate(qc, config);
        drainQueue(updateQueue, 5);

        Set<Set<String>> notifications = ConcurrentHashMap.newKeySet();
        testConfig.notifyOnDomainChange("test-multi-change", notifications::add);

        mockOrgConfig("cf-haus-organization-multi-changed.yml");
        fileWatcher.refresh(ctx, ORG_CONFIG_TASK_GROUP);
        drainQueue(updateQueue, 5);

        // Exactly one batched call, containing both changed project names
        assertThat(notifications).hasSize(1);
        assertThat(notifications.iterator().next()).containsExactlyInAnyOrder("foo", "bar");
        assertThat(testConfig.getDomainsForProject("foo")).containsExactlyInAnyOrder("foo.org", "foo.com");
        assertThat(testConfig.getDomainsForProject("bar")).containsExactly("bar.net");
    }

    @Test
    void testReadFailureAlertsAndLeavesCacheUntouched() throws Exception {
        mockOrgConfig("cf-haus-organization.yml");
        HausKeeperConfig config = loadConfig("cf-haus-keeper-org-source.yml");
        testConfig.testUpdate(qc, config);
        drainQueue(updateQueue, 5);
        assertThat(testConfig.getDomainsForProject("foo")).containsExactly("foo.org");
        mailbox.clear();

        // Simulate a read failure: file no longer present at the expected path
        org.mockito.Mockito.doReturn(null).when(hausMocks.repository()).getFileContent(ORG_CONFIG_PATH);

        fileWatcher.refresh(ctx, ORG_CONFIG_TASK_GROUP);
        drainQueue(updateQueue, 5);
        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() > 0);

        // Alerted via the bot error address (matches updateValidAttestations' convention)
        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).isNotEmpty();
        // Cache retains last-known-good data, not cleared
        assertThat(testConfig.getDomainsForProject("foo")).containsExactly("foo.org");
        assertThat(testConfig.getDomainsForProject("bar")).containsExactly("bar.org");
    }

    @Test
    void testAbsentOrganizationConfigDoesNotAlert() throws Exception {
        HausKeeperConfig config = loadConfig("cf-haus-keeper.yml");
        mailbox.clear();

        testConfig.testUpdate(qc, config);
        drainQueue(updateQueue, 5);

        assertThat(mailbox.getTotalMessagesSent()).isZero();
        assertThat(testConfig.getDomainsForProject("foo")).isEmpty();
    }

    @Test
    void testDiffTreatsRemovedProjectAsChange() throws Exception {
        mockOrgConfig("cf-haus-organization.yml");
        HausKeeperConfig config = loadConfig("cf-haus-keeper-org-source.yml");
        testConfig.testUpdate(qc, config);
        drainQueue(updateQueue, 5);

        Set<Set<String>> notifications = ConcurrentHashMap.newKeySet();
        testConfig.notifyOnDomainChange("test-removed-project", notifications::add);

        // "bar" is entirely absent from this fixture
        mockOrgConfig("cf-haus-organization-alt.yml");
        fileWatcher.refresh(ctx, ORG_CONFIG_TASK_GROUP);
        drainQueue(updateQueue, 5);

        assertThat(notifications).hasSize(1);
        assertThat(notifications.iterator().next()).containsExactlyInAnyOrder("foo", "bar");
        assertThat(testConfig.getDomainsForProject("foo")).containsExactly("foo.alt");
        assertThat(testConfig.getDomainsForProject("bar")).isEmpty();
    }
}
