package org.commonhaus.automation.hk.github;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import org.commonhaus.automation.github.context.BaseQueryCache;
import org.commonhaus.automation.github.context.ContextHelper;
import org.commonhaus.automation.github.context.DataLabel;
import org.commonhaus.automation.github.context.TestBotConfig;
import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.hk.ActiveHausKeeperConfig;
import org.commonhaus.automation.hk.AdminDataCache;
import org.commonhaus.automation.hk.api.MemberSession;
import org.commonhaus.automation.hk.config.HausKeeperConfig;
import org.commonhaus.automation.hk.member.MemberApplicationProcess;
import org.commonhaus.automation.hk.member.MemberInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHContentBuilder;
import org.kohsuke.github.GHContentUpdateResponse;
import org.kohsuke.github.GHEmail;
import org.kohsuke.github.GHMyself;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.mockito.Mockito;

import io.quarkiverse.githubapp.TokenGitHubClients;
import io.quarkiverse.githubapp.testing.dsl.GitHubMockSetupContext;

public class HausKeeperTestBase extends ContextHelper {

    static final DefaultValues DEFAULTS = new DefaultValues(
            -3,
            new Resource(144493209, "O_kgDOCJzKmQ", "commonhaus"),
            new Resource("commonhaus/foundation"));

    public static final long sponsorsInstallationId = 50263360;
    public static final long sponsorsOrgId = 144493209;
    public static final String sponsorsOrgName = "commonhaus-test";
    public static final String sponsorsRepo = "commonhaus-test/sponsors-test";
    public static final String sponsorsRepoId = "R_sponsors";

    public static final DefaultValues SPONSORS = new DefaultValues(
            sponsorsInstallationId,
            new Resource(sponsorsOrgId, sponsorsOrgName),
            new Resource(sponsorsRepoId, sponsorsRepo),
            false);

    public static final long datastoreInstallationId = -5;
    public static final long datastoreOrganizationId = 801851090;
    public static final String datastoreOrgName = "datastore";
    public static final String datastoreRepoName = "datastore/org";
    public static final String datastoreRepoId = "R_datastore";

    public static final DefaultValues DATASTORE = new DefaultValues(
            datastoreInstallationId,
            new Resource(datastoreOrganizationId, datastoreOrgName),
            new Resource(datastoreRepoId, datastoreRepoName),
            false);

    public static final long botId = 156364140;
    public static final String botLogin = "commonhaus-bot";
    public static final String botNodeId = "U_kgDOCVHtbA";

    public static final DataLabel APP_NEW = new DataLabel.Builder()
            .name(MemberApplicationProcess.NEW).build();
    public static final DataLabel APP_ACCEPTED = new DataLabel.Builder()
            .name(MemberApplicationProcess.ACCEPTED).build();
    public static final DataLabel APP_DECLINED = new DataLabel.Builder()
            .id("LA_kwDOKRPTI88AAAABhGp_7g")
            .name(MemberApplicationProcess.DECLINED).build();
    public static final Set<DataLabel> APP_LABELS = Set.of(APP_NEW, APP_ACCEPTED, APP_DECLINED);

    @Inject
    protected AppContextService ctx;

    @Inject
    protected TestUserManagementConfig testConfig;

    protected MockInstallation dataMocks;
    protected MockInstallation sponsorMocks;

    protected MemberInfo mockMemberInfo;

    @BeforeEach
    protected void init() throws Exception {
        reset();
        Stream.of(AdminDataCache.values()).forEach(AdminDataCache::invalidateAll);
        setLabels(datastoreRepoId, APP_LABELS);
    }

    @AfterEach
    void waitForQueue() {
        // Make sure queue is drained between tests
        await().atMost(5, SECONDS).until(() -> updateQueue.isEmpty());
    }

    public void setupInstallationRepositories() throws IOException {
        setupInstallationRepositories(null);
    }

    public void setupInstallationRepositories(GitHubMockSetupContext mocks) throws IOException {
        this.mocks = mocks;

        HausKeeperConfig config = ctx.yamlMapper().readValue(
                ContextHelper.class.getResourceAsStream("/cf-haus-keeper.yml"), HausKeeperConfig.class);
        testConfig.initConfig(config);

        setupDefaultMocks(DEFAULTS);
        updateConnection(DEFAULTS.installId(), hausMocks.github());
        updateConnection(DEFAULTS.installId(), hausMocks.dql());

        triggerRepositoryDiscovery(DiscoveryAction.ADDED, hausMocks, false);

        dataMocks = setupInstallationMocks(DATASTORE);
        updateConnection(DATASTORE.installId(), dataMocks.github());
        updateConnection(DATASTORE.installId(), dataMocks.dql());

        triggerRepositoryDiscovery(DiscoveryAction.ADDED, dataMocks, false);

        sponsorMocks = setupInstallationMocks(SPONSORS);
        updateConnection(SPONSORS.installId(), sponsorMocks.github());
        updateConnection(SPONSORS.installId(), sponsorMocks.dql());

        triggerRepositoryDiscovery(DiscoveryAction.ADDED, sponsorMocks, false);
    }

    public void setUserAsUnknown(String login) {
        AdminDataCache.KNOWN_USER.put("user-" + botId, Boolean.FALSE);
    }

    public void addCollaborator(String repoName, String login) {
        BaseQueryCache.COLLABORATORS.put(repoName, Set.of(login));
    }

    public GHUser setupBotLogin() throws IOException {

        // Set up the bot login in the sponsor/user repo

        GitHub gh = sponsorMocks.github();
        GHUser bot = mockUser(botLogin, botId, botNodeId, gh);

        GHEmail email = mock(GHEmail.class);
        when(email.isPrimary()).thenReturn(true);
        when(email.getEmail()).thenReturn("test@example.com");

        GHMyself myself = mock(GHMyself.class);
        when(myself.getEmails2()).thenReturn(List.of(email));

        when(sponsorMocks.github().getMyself()).thenReturn(myself);

        // Pre-set the connection for the bot user session

        MemberSession.updateUserConnection(botNodeId, gh);

        // Make sure the same bot user is returned everywhere

        when(dataMocks.github().getUser(botLogin)).thenReturn(bot);
        when(dataMocks.github().getMyself()).thenReturn(myself);

        when(hausMocks.github().getUser(botLogin)).thenReturn(bot);
        when(hausMocks.github().getMyself()).thenReturn(myself);

        // Special token is used for r/w to datastore repo

        ctx.tokenClients = Mockito.mock(TokenGitHubClients.class);
        when(ctx.tokenClients.getRestClient()).thenReturn(dataMocks.github());
        when(ctx.tokenClients.getGraphQLClient()).thenReturn(dataMocks.dql());

        // Add some default attestation ids

        testConfig.addAttestation("member");
        testConfig.addAttestation("coc");

        mockMemberInfo = mock(MemberInfo.class);
        when(mockMemberInfo.id()).thenReturn(botId);
        when(mockMemberInfo.login()).thenReturn(botLogin);
        when(mockMemberInfo.name()).thenReturn("Commonhaus Bot");
        when(mockMemberInfo.url()).thenReturn("https://example.com");
        when(mockMemberInfo.notificationEmail()).thenReturn(Optional.of("test@example.com"));

        return bot;
    }

    public void setupMockTeam() throws IOException {
        GitHub gh = sponsorMocks.github();

        Set<GHUser> testQuorum = new HashSet<>();
        Set<GHUser> council = new HashSet<>();
        Set<GHUser> voting = new HashSet<>();

        for (int i = 1; i < 15; i++) {
            String login = "user" + i;
            GHUser user = mockUser(login, gh);
            if (i % 2 == 0) {
                testQuorum.add(user);
            }
            if (i % 3 == 0) {
                council.add(user);
            }
            if (i % 6 == 0) {
                voting.add(user);
            }
        }
        mockTeam(sponsorMocks, "commonhaus-test/team-quorum-default", testQuorum);
        mockTeam(sponsorMocks, "commonhaus-test/cf-council", council);
        mockTeam(sponsorMocks, "commonhaus-test/cf-voting", voting);
    }

    public void setUserManagementConfig() throws Exception {
        HausKeeperConfig config = ctx.yamlMapper().readValue(
                ContextHelper.class.getResourceAsStream("/cf-haus-keeper.yml"), HausKeeperConfig.class);

        ScopedQueryContext qc = new ScopedQueryContext(ctx, datastoreInstallationId, dataMocks.repository());
        testConfig.testUpdate(qc, config);
    }

    public GHContent mockExistingCommonhausData(UserPath userPath) throws IOException {
        GHRepository dataStore = dataMocks.repository();
        return mockFileContent(dataStore, "data/users/" + botId + ".yaml", userPath.filename());
    }

    public GHContentBuilder mockUpdateCommonhausData(GHContentBuilder builder, UserPath userPath) throws IOException {
        GHContent content = mock(GHContent.class);
        return mockUpdateCommonhausData(builder, userPath, content);
    }

    public GHContentBuilder mockUpdateCommonhausData(GHContentBuilder builder, UserPath userPath,
            GHContent content) throws IOException {
        GHContentUpdateResponse response = Mockito.mock(GHContentUpdateResponse.class);
        return mockUpdateCommonhausData(builder, userPath, content, response);
    }

    public GHContentBuilder mockUpdateCommonhausData(GHContentBuilder builder, UserPath userPath,
            GHContent content, GHContentUpdateResponse response) throws IOException {
        when(content.read()).thenReturn(Files.newInputStream(Path.of(userPath.filename())));
        when(content.getSha()).thenReturn("1234567890adefgh");

        when(response.getContent()).thenReturn(content);
        when(builder.commit()).thenReturn(response);

        when(builder.content(anyString())).thenReturn(builder);
        when(builder.message(anyString())).thenReturn(builder);
        when(builder.path(anyString())).thenReturn(builder);
        when(builder.sha(anyString())).thenReturn(builder);

        GHRepository dataStore = dataMocks.repository();
        when(dataStore.createContent()).thenReturn(builder);

        // Return updated data
        return builder;
    }

    public enum UserPath {
        WITH_APPLICATION("src/test/resources/commonhaus-user.application.unknown.yaml"),
        WITH_ATTESTATION("src/test/resources/commonhaus-user.attestation.yaml"),
        NEW_USER("src/test/resources/commonhaus-user.new.yaml");

        private String filename;

        UserPath(String filename) {
            this.filename = filename;
        }

        public String filename() {
            return filename;
        }
    }

    public enum MemberQueryResponse implements MockResponse {
        APPLICATION_BAD_TITLE("query($id: ID!) {",
                "src/test/resources/github/queryIssue-ApplicationBadTitle.json"),

        APPLICATION_MATCH("query($id: ID!) {",
                "src/test/resources/github/queryIssue-ApplicationMatch.json"),

        APPLICATION_OTHER_OWNER("query($id: ID!) {",
                "src/test/resources/github/queryIssue-ApplicationOtherOwner.json"),

        QUERY_COMMENTS("comments(first: 50",
                "src/test/resources/github/queryComments.json"),

        QUERY_NO_COMMENTS("comments(first: 50",
                "src/test/resources/github/queryComments.None.json"),

        CREATE_ISSUE("createIssue(input: {",
                "src/test/resources/github/mutableCreateIssue.json"),

        UPDATE_ISSUE("updateIssue(input: {",
                "src/test/resources/github/mutableUpdateIssue.json"),
                ;

        String cue;
        Path path;

        MemberQueryResponse(String cue, String path) {
            this.cue = cue;
            this.path = Path.of(path);
        }

        @Override
        public String cue() {
            return cue;
        }

        @Override
        public Path path() {
            return path;
        }

        @Override
        public long installationId() {
            return datastoreInstallationId;
        }
    }

    @ApplicationScoped
    @Alternative
    @Priority(5) // Higher priority than the one in the core module
    public static class HKTestBotConfig extends TestBotConfig {
        private String tempJournalPath;
        private String tempJournalFile;

        HKTestBotConfig() {
            try {
                // Create target directory if it doesn't exist
                Path targetDir = Paths.get("target/test-state");
                Files.createDirectories(targetDir);
                Path tempDir = Files.createTempDirectory(targetDir, "test-");
                tempJournalPath = tempDir.toString();

                // Create a temp file with a prefix and suffix in that directory
                // Register for deletion on JVM exit
                Path tempFile = tempDir.resolve("commonhaus-journal.yaml");
                tempJournalFile = tempFile.toString();

                tempFile.toFile().deleteOnExit();
                tempDir.toFile().deleteOnExit();
                System.out.println("Using temp journal directory: " + tempDir);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create temp journal file", e);
            }
        }

        public Path getTempJournalFile() {
            return Paths.get(tempJournalFile);
        }

        @Override
        public QueueConfig queue() {
            return new QueueConfig() {
                @Override
                public Duration initialDelay() {
                    return Duration.ofMillis(1);
                }

                @Override
                public Duration period() {
                    return Duration.ofMillis(1);
                }

                @Override
                public Optional<String> stateDirectory() {
                    return Optional.of(tempJournalPath);
                }

                @Override
                public Optional<String> stateFile() {
                    return Optional.empty();
                }
            };
        }
    }

    @ApplicationScoped
    @Alternative
    @Priority(1)
    static class TestUserManagementConfig extends ActiveHausKeeperConfig {
        @Inject
        public TestUserManagementConfig() {
            super();
        }

        public void addAttestation(String id) {
            attestationIds.add(id);
        }

        public void initConfig(HausKeeperConfig config) {
            currentConfig.set(Optional.of(config));
            // Queue callbacks for config consumers
            for (var callback : callbacks.entrySet()) {
                callback.getValue();
            }
        }

        public void testUpdate(ScopedQueryContext qc, HausKeeperConfig config) {
            update(qc, config);
        }
    }
}
