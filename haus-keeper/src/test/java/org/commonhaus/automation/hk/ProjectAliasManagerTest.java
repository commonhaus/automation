package org.commonhaus.automation.hk;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.inject.Inject;

import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.github.watchers.FileWatcher;
import org.commonhaus.automation.hk.config.HausKeeperConfig;
import org.commonhaus.automation.hk.config.ProjectAliasMapping;
import org.commonhaus.automation.hk.config.ProjectAliasMapping.UserAliasList;
import org.commonhaus.automation.hk.github.AppContextService;
import org.commonhaus.automation.hk.github.HausKeeperTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHContentBuilder;
import org.kohsuke.github.GHContentUpdateResponse;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class ProjectAliasManagerTest extends HausKeeperTestBase {

    static final String ORG_CONFIG_REPO = "commonhaus/foundation";
    static final String ORG_CONFIG_PATH = "cf-haus-organization.yml";

    @Inject
    AppContextService ctx;

    @Inject
    FileWatcher fileWatcher;

    @Inject
    HausKeeperTestBase.TestFileWatcher testFileWatcher;

    @Inject
    ProjectAliasManager projectAliasManager;

    @BeforeEach
    void setupProjectAliasManagerTest() throws Exception {
        // taskGroupToState, the org-domains cache, and FileWatcher's registered
        // watches are all singleton CDI bean state shared across every test
        // class in the same JVM/build (e.g. ActiveHausKeeperConfigTest runs in
        // the same instance), so stale state from a prior test/class must be
        // cleared first.
        projectAliasManager.taskGroupToState.clear();
        testConfig.testResetOrganizationDomains();
        testFileWatcher.testReset();
        setupInstallationRepositories();
        setupBotLogin();
    }

    @Test
    void testDomainConfiguration() {
        // Single domain (backward compatibility)
        ProjectAliasMapping singleDomain = new ProjectAliasMapping(
                "example.com",
                null,
                Set.of(new UserAliasList("user1", Set.of("alias@example.com"))),
                null);
        assertThat(singleDomain.domains()).containsExactly("example.com");
        assertThat(singleDomain.isEnabled()).isTrue();

        // Multiple domains
        Set<String> domains = Set.of("example.com", "example.org");
        ProjectAliasMapping multiDomain = new ProjectAliasMapping(
                null,
                domains,
                Set.of(new UserAliasList("user1", Set.of("alias@example.com"))),
                null);
        assertThat(multiDomain.domains()).containsExactlyInAnyOrder("example.com", "example.org");
        assertThat(multiDomain.isEnabled()).isTrue();

        // Disabled: empty/null domains or mappings
        Set<String> emptyDomains = Set.of();
        assertThat(new ProjectAliasMapping(null, emptyDomains, Set.of(new UserAliasList("u", Set.of("a@b.com"))), null)
                .isEnabled()).isFalse();
        assertThat(new ProjectAliasMapping(null, domains, Set.of(), null).isEnabled()).isFalse();
    }

    @Test
    void testAliasValidation() {
        Set<String> singleDomain = Set.of("example.com");
        Set<String> multiDomain = Set.of("example.com", "example.org");

        // Valid cases
        assertThat(new UserAliasList("user1", Set.of("a@example.com", "b@example.com"))
                .isValid(singleDomain)).isTrue();
        assertThat(new UserAliasList("user2", Set.of("a@example.com", "b@example.org"))
                .isValid(multiDomain)).isTrue();

        // Invalid: wrong domain
        assertThat(new UserAliasList("user3", Set.of("a@example.com", "b@wrong.com"))
                .isValid(singleDomain)).isFalse();
        assertThat(new UserAliasList("user4", Set.of("a@wrong.com"))
                .isValid(multiDomain)).isFalse();

        // Invalid: bad login or aliases
        assertThat(new UserAliasList(null, Set.of("a@example.com")).isValid(singleDomain)).isFalse();
        assertThat(new UserAliasList("", Set.of("a@example.com")).isValid(singleDomain)).isFalse();
        assertThat(new UserAliasList("user", Set.of()).isValid(singleDomain)).isFalse();
        assertThat(new UserAliasList("user", null).isValid(singleDomain)).isFalse();
    }

    @Test
    void testToProjectName() {
        assertThat(ProjectAliasManager.toProjectName("commonhaus/project-foo")).isEqualTo("foo");
        assertThat(ProjectAliasManager.toProjectName("commonhaus/foo")).isEqualTo("foo");
        assertThat(ProjectAliasManager.toProjectName("project-foo")).isEqualTo("foo");
        assertThat(ProjectAliasManager.toProjectName("foo")).isEqualTo("foo");
    }

    @Test
    void testYamlDeserializationBackwardCompatibility() throws Exception {
        // Test old format: singular "domain"
        String oldFormatYaml = """
                domain: example.com
                userMapping:
                  - login: user1
                    aliases:
                      - alias1@example.com
                      - alias2@example.com
                """;

        ProjectAliasMapping oldFormat = ctx.yamlMapper().readValue(oldFormatYaml, ProjectAliasMapping.class);

        assertThat(oldFormat.domains()).containsExactly("example.com");
        assertThat(oldFormat.userMapping()).hasSize(1);
        assertThat(oldFormat.isEnabled()).isTrue();

        // Test new format: plural "domains"
        String newFormatYaml = """
                domains:
                  - example.com
                  - example.org
                userMapping:
                  - login: user1
                    aliases:
                      - alias1@example.com
                      - alias2@example.org
                """;

        ProjectAliasMapping newFormat = ctx.yamlMapper().readValue(newFormatYaml, ProjectAliasMapping.class);
        assertThat(newFormat.domains()).containsExactlyInAnyOrder("example.com", "example.org");
        assertThat(newFormat.userMapping()).hasSize(1);
        assertThat(newFormat.isEnabled()).isTrue();

        // Verify validation works correctly for both
        UserAliasList userFromOld = oldFormat.userMapping().iterator().next();
        assertThat(userFromOld.isValid(oldFormat.domains())).isTrue();

        UserAliasList userFromNew = newFormat.userMapping().iterator().next();
        assertThat(userFromNew.isValid(newFormat.domains())).isTrue();
    }

    // -----------------------------------------------------------------
    // Integration tests for domain filtering, fail-open, and batched
    // reporting in ProjectAliasManager.reconcile.
    //
    // Project repos live in the home org, where haus-keeper only has
    // read-only access; user records and haus-keeper's own config live in
    // the separate datastore org, where it has write access (a deliberate
    // blast-radius boundary). cf-haus-organization.yml also lives in the
    // home org, alongside the projects it describes -- it's read from
    // hausMocks' repo (commonhaus/foundation), matching
    // ActiveHausKeeperConfigTest's own setup.
    // -----------------------------------------------------------------

    /** Registers a new project repository under the home org/installation. */
    GHRepository mockProjectRepo(String repoFullName) throws Exception {
        return mockRepository(repoFullName, dataMocks.github());
    }

    void mockOrgConfig(String fixtureFileName) throws Exception {
        mockFileContent(hausMocks.repository(), ORG_CONFIG_PATH,
                Path.of("src/test/resources/" + fixtureFileName));
    }

    HausKeeperConfig configWithOrgSource() throws Exception {
        String yaml = """
                userManagement:
                  defaultAliasDomain: example.com
                  attestations:
                    repository: commonhaus/foundation
                    filePath: ATTESTATIONS.yaml
                  organizationConfig:
                    repository: %s
                    filePath: %s
                emailNotifications:
                  errors:
                    - repo-errors@example.com
                """.formatted(ORG_CONFIG_REPO, ORG_CONFIG_PATH);
        return ctx.yamlMapper().readValue(yaml, HausKeeperConfig.class);
    }

    /** Reads cf-haus-organization.yml into the (real, injected) ActiveHausKeeperConfig cache. */
    void loadOrganizationDomains(String fixtureFileName) throws Exception {
        mockOrgConfig(fixtureFileName);
        ScopedQueryContext homeQc = new ScopedQueryContext(ctx, hausMocks.installationId(), hausMocks.repository());
        testConfig.testUpdate(homeQc, configWithOrgSource());
        drainQueue(updateQueue, 5);
    }

    /** Registers a project repo, mocks project-mail-aliases.yml, and triggers discovery (read + queued reconcile). */
    void discoverProject(String repoFullName, String projectAliasesFixture) throws Exception {
        GHRepository projectRepo = mockProjectRepo(repoFullName);
        mockFileContent(projectRepo, ProjectAliasMapping.CONFIG_FILE,
                Path.of("src/test/resources/" + projectAliasesFixture));

        var projectMocks = new MockInstallation(
                datastoreInstallationId, dataMocks.github(), dataMocks.dql(),
                dataMocks.organization(), projectRepo, null);
        triggerRepositoryDiscovery(DiscoveryAction.ADDED, projectMocks, projectRepo, false);
        drainQueue(updateQueue, 5);
    }

    GHUser mockProjectUser(String login) throws Exception {
        return mockUser(login, dataMocks.github());
    }

    /**
     * Mocks the datastore write path (getCommonhausUser create + setCommonhausUser persist)
     * for a brand-new user. The login is not otherwise significant to the mock (the response
     * content is a fixed fixture), but is accepted for readability at call sites.
     */
    void mockDatastoreWriteFor(@SuppressWarnings("unused") String login) throws Exception {
        GHContentBuilder builder = mock(GHContentBuilder.class);
        mockUpdateCommonhausData(builder, UserPath.NEW_USER);
    }

    /**
     * Mocks the datastore write path for multiple new users reconciled in the
     * same pass. dataStore.createContent() takes no arguments, so a single
     * mockDatastoreWriteFor-per-user call would have the last stub silently
     * win for every user; this registers one builder per login, returned in
     * call order (matching reconcile's per-user processing order).
     */
    void mockSequentialDatastoreWrites(String... logins) throws Exception {
        GHContentBuilder[] builders = new GHContentBuilder[logins.length];
        for (int i = 0; i < logins.length; i++) {
            GHContentBuilder builder = mock(GHContentBuilder.class);
            GHContent content = mock(GHContent.class);
            GHContentUpdateResponse response = mock(GHContentUpdateResponse.class);
            doReturn(Files.newInputStream(Path.of(UserPath.NEW_USER.filename())))
                    .when(content).read();
            doReturn("1234567890adefgh").when(content).getSha();
            doReturn(content).when(response).getContent();
            doReturn(response).when(builder).commit();
            doReturn(builder).when(builder).content(anyString());
            doReturn(builder).when(builder).message(anyString());
            doReturn(builder).when(builder).path(anyString());
            doReturn(builder).when(builder).sha(anyString());
            builders[i] = builder;
        }
        GHContentBuilder first = builders[0];
        GHContentBuilder[] rest = Arrays.copyOfRange(builders, 1, builders.length);
        doReturn(first, (Object[]) rest).when(dataMocks.repository()).createContent();
    }

    @Test
    void testDomainFilteringAcceptsAuthoritativeAndRejectsNonAuthoritative() throws Exception {
        // authoritative: foo -> {foo.org}; self-declared: {foo.org, bar.org}
        loadOrganizationDomains("cf-haus-organization.yml");
        setupGraphQLProcessing(dataMocks, MemberQueryResponse.CREATE_ISSUE);

        mockProjectUser("alice");
        mockProjectUser("bob");
        mockDatastoreWriteFor("alice");

        mailbox.clear();
        discoverProject("datastore/project-foo", "project-mail-aliases-foo.yml");

        // alice@foo.org is on an authoritative domain: accepted, no invalid-alias report
        // bob@bar.org is self-declared but not authoritative: rejected, batched report
        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() > 0);

        assertThat(mailbox.getMailsSentTo("repo-errors@example.com")).hasSize(1);
        String body = mailbox.getMailsSentTo("repo-errors@example.com").get(0).getText();
        assertThat(body).contains("bob");
        // alice is on an authoritative domain and not reported as invalid --
        // she may still appear in the trailing "Project config: ..." dump
        // (the full self-declared config, for context), so check the findings
        // section specifically rather than the whole message body.
        assertThat(body).doesNotContain("Login: alice");

        verify(dataMocks.dql(), times(1))
                .executeSync(contains("createIssue(input: {"), anyMap());
    }

    @Test
    void testSelfDeclaredDomainsAlreadySubsetOfAuthoritativeIsUnaffected() throws Exception {
        // authoritative: bar -> {bar.org}; self-declared: {bar.org} (already a subset)
        loadOrganizationDomains("cf-haus-organization.yml");

        mockProjectUser("carol");
        mockDatastoreWriteFor("carol");

        mailbox.clear();
        discoverProject("datastore/project-bar", "project-mail-aliases-bar-subset.yml");

        // No invalid aliases; behavior identical to pre-feature (no filtering effect)
        assertNoErrorEmails();
    }

    @Test
    void testMultipleInvalidUsersProduceExactlyOneEmailAndOneIssue() throws Exception {
        // authoritative: foo -> {foo.org}; both users use wrong.org (neither self-declared nor authoritative)
        loadOrganizationDomains("cf-haus-organization.yml");
        setupGraphQLProcessing(dataMocks, MemberQueryResponse.CREATE_ISSUE);

        mockProjectUser("dave");
        mockProjectUser("erin");

        mailbox.clear();
        discoverProject("datastore/project-foo", "project-mail-aliases-multi-invalid.yml");

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() > 0);

        // Exactly one batched email, listing both invalid users, not one per user
        assertThat(mailbox.getMailsSentTo("repo-errors@example.com")).hasSize(1);
        String body = mailbox.getMailsSentTo("repo-errors@example.com").get(0).getText();
        assertThat(body).contains("dave").contains("erin");

        // Exactly one issue created for the pass
        verify(dataMocks.dql(), times(1))
                .executeSync(contains("createIssue(input: {"), anyMap());
    }

    @Test
    void testUserMappingPresentWithoutDomainsDoesNotThrowAndReportsInvalid() throws Exception {
        // authoritative domains ARE cached for "foo" (so the filtering block is reached),
        // but the project's own project-mail-aliases.yml declares userMapping with neither
        // "domain" nor "domains" set -- domains() is null for this config shape.
        loadOrganizationDomains("cf-haus-organization.yml");
        setupGraphQLProcessing(dataMocks, MemberQueryResponse.CREATE_ISSUE);

        mockProjectUser("frank");

        mailbox.clear();
        // Must not throw (NPE) despite authoritativeDomains being non-empty for "foo".
        discoverProject("datastore/project-foo", "project-mail-aliases-no-domains.yml");

        // Pre-feature-equivalent outcome: frank's alias can never validate against an
        // empty domain set, so it is reported via the normal batched invalid-alias path,
        // not silently skipped and not a crash.
        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() > 0);
        assertThat(mailbox.getMailsSentTo("repo-errors@example.com")).hasSize(1);
        String body = mailbox.getMailsSentTo("repo-errors@example.com").get(0).getText();
        assertThat(body).contains("frank");

        verify(dataMocks.dql(), times(1))
                .executeSync(contains("createIssue(input: {"), anyMap());
    }

    @Test
    void testOrganizationConfigReadFailureFailsOpenAndAlerts() throws Exception {
        // organizationConfig is configured but the file read fails (not found)
        ScopedQueryContext homeQc = new ScopedQueryContext(ctx, hausMocks.installationId(), hausMocks.repository());
        doReturn(null).when(hausMocks.repository()).getFileContent(ORG_CONFIG_PATH);
        mailbox.clear();
        testConfig.testUpdate(homeQc, configWithOrgSource());
        drainQueue(updateQueue, 5);
        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() > 0);

        // Read failure is alerted (fail-open source failure, distinct from "absent" below)
        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).isNotEmpty();
        // fail-open: getDomainsForProject returns empty (no data cached) for any project
        assertThat(testConfig.getDomainsForProject("foo")).isEmpty();

        mockProjectUser("alice");
        mockProjectUser("bob");
        mockSequentialDatastoreWrites("alice", "bob");

        mailbox.clear();
        discoverProject("datastore/project-foo", "project-mail-aliases-foo.yml");

        // Self-declared domains used unfiltered: both alice@foo.org and bob@bar.org are
        // accepted since authoritativeDomains is empty (fail-open) -- no invalid-alias
        // report caused by filtering, and both reach the (mocked) datastore write path.
        assertNoErrorEmails();
    }

    @Test
    void testOrganizationConfigAbsentFailsOpenWithoutAlert() throws Exception {
        // organizationConfig omitted entirely: same fail-open unfiltered behavior, no alert.
        // attestations is included (empty RepoSource, not absent) since updateValidAttestations
        // assumes a non-null attestations() -- unrelated pre-existing behavior, not under test here.
        String yaml = """
                userManagement:
                  defaultAliasDomain: example.com
                  attestations:
                    repository: commonhaus/foundation
                    filePath: ATTESTATIONS.yaml
                emailNotifications:
                  errors:
                    - repo-errors@example.com
                """;
        HausKeeperConfig config = ctx.yamlMapper().readValue(yaml, HausKeeperConfig.class);
        ScopedQueryContext homeQc = new ScopedQueryContext(ctx, hausMocks.installationId(), hausMocks.repository());

        mailbox.clear();
        testConfig.testUpdate(homeQc, config);
        drainQueue(updateQueue, 5);

        assertNoErrorEmails();
        assertThat(testConfig.getDomainsForProject("foo")).isEmpty();

        mockProjectUser("alice");
        mockProjectUser("bob");
        mockSequentialDatastoreWrites("alice", "bob");

        mailbox.clear();
        discoverProject("datastore/project-foo", "project-mail-aliases-foo.yml");

        // Unfiltered: both alice@foo.org and bob@bar.org self-declared domains accepted
        // (no authoritative data at all -> fail-open); no invalid-alias report.
        assertNoErrorEmails();
    }

    @Test
    void testDomainRemovedTriggersTargetedReconcileOfAffectedProjectOnly() throws Exception {
        loadOrganizationDomains("cf-haus-organization.yml");
        setupGraphQLProcessing(dataMocks, MemberQueryResponse.CREATE_ISSUE);

        // Provision alias while foo.org is authoritative for "foo"
        mockProjectUser("alice");
        mockDatastoreWriteFor("alice");
        discoverProject("datastore/project-foo", "project-mail-aliases-foo.yml");

        // Also discover "bar" (subset case; must remain untouched by the foo-only change)
        mockProjectUser("carol");
        mockDatastoreWriteFor("carol");
        discoverProject("datastore/project-bar", "project-mail-aliases-bar-subset.yml");

        mailbox.clear();
        clearInvocations(dataMocks.dql());

        // Directly observe which project names the domain-change diff reports as changed,
        // independent of reconcile's own downstream effects -- this is what distinguishes
        // "bar was never re-triggered" from "bar was re-triggered but happened to produce
        // no report," which the batched-email assertion alone cannot rule out.
        Set<Set<String>> changedProjectNotifications = ConcurrentHashMap.newKeySet();
        testConfig.notifyOnDomainChange("test-targeted-reconcile", changedProjectNotifications::add);

        // cf-haus-organization.yml is updated: foo's authoritative domain changes from
        // foo.org to foo.net; bar is untouched. Simulate via FileWatcher refresh.
        mockOrgConfig("cf-haus-organization-domain-removed.yml");
        fileWatcher.refresh(ctx, ActiveHausKeeperConfig.ORG_CONFIG_TASK_GROUP);
        drainQueue(updateQueue, 5);

        // Only "foo" is reported as changed -- "bar" is not re-triggered at all.
        assertThat(changedProjectNotifications).hasSize(1);
        assertThat(changedProjectNotifications.iterator().next()).containsExactly("foo");

        // foo's previously-valid alice@foo.org alias is now invalid (foo.org no longer authoritative);
        // bar's carol@bar.org alias is untouched (bar's authoritative set did not change), so exactly
        // one batched report is produced (for foo only), not one for every known project.
        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() > 0);
        assertThat(mailbox.getMailsSentTo("repo-errors@example.com")).hasSize(1);
        String body = mailbox.getMailsSentTo("repo-errors@example.com").get(0).getText();
        assertThat(body).contains("alice");
    }
}
