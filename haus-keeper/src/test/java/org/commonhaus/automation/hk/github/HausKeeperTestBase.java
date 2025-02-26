package org.commonhaus.automation.hk.github;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.commonhaus.automation.github.context.BaseQueryCache;
import org.commonhaus.automation.github.context.ContextHelper;
import org.commonhaus.automation.github.context.DataLabel;
import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.hk.AdminDataCache;
import org.commonhaus.automation.hk.api.MemberApplicationProcess;
import org.commonhaus.automation.hk.api.MemberSession;
import org.commonhaus.automation.hk.config.HausKeeperConfig;
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

    protected MockInstallation dataMocks;
    protected MockInstallation sponsorMocks;

    @BeforeEach
    protected void init() throws Exception {
        reset();
        Stream.of(AdminDataCache.values()).forEach(AdminDataCache::invalidateAll);
        setLabels(datastoreRepoId, APP_LABELS);
    }

    public void setupInstallationRepositories() throws IOException {
        setupInstallationRepositories(null);
    }

    public void setupInstallationRepositories(GitHubMockSetupContext mocks) throws IOException {
        this.mocks = mocks;

        setupDefaultMocks(DEFAULTS);
        ctx.updateConnection(DEFAULTS.installId(), hausMocks.github());
        ctx.updateConnection(DEFAULTS.installId(), hausMocks.dql());

        triggerRepositoryDiscovery(DiscoveryAction.ADDED, hausMocks, false);

        dataMocks = setupInstallationMocks(DATASTORE);
        ctx.updateConnection(DATASTORE.installId(), dataMocks.github());
        ctx.updateConnection(DATASTORE.installId(), dataMocks.dql());

        triggerRepositoryDiscovery(DiscoveryAction.ADDED, dataMocks, false);

        sponsorMocks = setupInstallationMocks(SPONSORS);
        ctx.updateConnection(SPONSORS.installId(), sponsorMocks.github());
        ctx.updateConnection(SPONSORS.installId(), sponsorMocks.dql());

        triggerRepositoryDiscovery(DiscoveryAction.ADDED, sponsorMocks, false);
    }

    public void setUserAsUnknown(String login) {
        AdminDataCache.KNOWN_USER.put(login, Boolean.FALSE);
    }

    public void addCollaborator(String repoName, String login) {
        BaseQueryCache.COLLABORATORS.put(repoName, Set.of(login));
    }

    public GitHub setupBotLogin() throws IOException {

        // Set up the bot login in the sponsor/user repo

        GitHub gh = sponsorMocks.github();
        GHUser bot = mockUser(botLogin, gh);

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
        when(hausMocks.github().getUser(botLogin)).thenReturn(bot);

        // Special token is used for r/w to datastore repo

        ctx.tokenClients = Mockito.mock(TokenGitHubClients.class);
        when(ctx.tokenClients.getRestClient()).thenReturn(dataMocks.github());
        when(ctx.tokenClients.getGraphQLClient()).thenReturn(dataMocks.dql());

        // Add some default attestation ids

        ctx.attestationIds.add("member");
        ctx.attestationIds.add("coc");

        return gh;
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
                ContextHelper.class.getResourceAsStream("/cf-admin.yml"), HausKeeperConfig.class);
        ctx.currentConfig.set(Optional.of(config));
    }

    public void mockExistingCommonhausData() throws IOException {
        mockExistingCommonhausData("src/test/resources/commonhaus-user.yaml");
    }

    public void mockExistingCommonhausData(String filename) throws IOException {
        GHContent content = mock(GHContent.class);
        when(content.read()).thenReturn(Files.newInputStream(Path.of(filename)));
        when(content.getSha()).thenReturn("1234567890abcdef");

        GHRepository dataStore = dataMocks.repository();
        when(dataStore.getFileContent(anyString())).thenReturn(content);
    }

    public GHContentBuilder mockUpdateCommonhausData() throws IOException {
        GHContentBuilder builder = Mockito.mock(GHContentBuilder.class);
        return mockUpdateCommonhausData(builder);
    }

    public GHContentBuilder mockUpdateCommonhausData(String filename) throws IOException {
        GHContentBuilder builder = Mockito.mock(GHContentBuilder.class);
        return mockUpdateCommonhausData(builder, filename);
    }

    public GHContentBuilder mockUpdateCommonhausData(GHContentBuilder builder)
            throws IOException {
        return mockUpdateCommonhausData(builder, "src/test/resources/commonhaus-user.yaml");
    }

    public GHContentBuilder mockUpdateCommonhausData(GHContentBuilder builder, String filename) throws IOException {
        GHContent responseContent = mock(GHContent.class);
        when(responseContent.read()).thenReturn(Files.newInputStream(Path.of(filename)));
        when(responseContent.getSha()).thenReturn("1234567890adefgh");

        GHContentUpdateResponse response = Mockito.mock(GHContentUpdateResponse.class);
        when(response.getContent()).thenReturn(responseContent);

        when(builder.content(anyString())).thenReturn(builder);
        when(builder.message(anyString())).thenReturn(builder);
        when(builder.path(anyString())).thenReturn(builder);
        when(builder.sha(anyString())).thenReturn(builder);
        when(builder.commit()).thenReturn(response);

        GHRepository dataStore = dataMocks.repository();
        when(dataStore.createContent()).thenReturn(builder);

        // Return updated data
        return builder;
    }
}
