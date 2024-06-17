package org.commonhaus.automation.admin.github;

import static org.commonhaus.automation.github.context.BaseQueryCache.TEAM_MEMBERS;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.inject.Singleton;
import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.commonhaus.automation.admin.AdminDataCache;
import org.commonhaus.automation.admin.api.ApplicationData;
import org.commonhaus.automation.admin.config.AdminConfigFile;
import org.commonhaus.automation.github.context.BaseQueryCache;
import org.commonhaus.automation.github.context.DataLabel;
import org.commonhaus.automation.github.context.QueryContext;
import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHContentBuilder;
import org.kohsuke.github.GHContentUpdateResponse;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTeam;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.mockito.Mockito;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonGenerator.Feature;
import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.DatabindException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.VisibilityChecker;

import io.quarkiverse.githubapp.testing.dsl.GitHubMockSetupContext;
import io.quarkus.jackson.ObjectMapperCustomizer;
import io.smallrye.graphql.client.Response;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public class ContextHelper extends QueryContext {

    public static final long sponsorsInstallationId = 50263360;
    public static final long sponsorsOrgId = 144493209;
    public static final String sponsorsOrgName = "commonhaus-test";
    public static final String sponsorsRepo = "commonhaus-test/sponsors-test";
    public static final String sponsorsRepoId = "R_sponsors";

    public static final long datastoreInstallationId = 50264360;
    public static final long datastoreOrganizationId = 801851090;
    public static final String datastoreOrgName = "datastore";
    public static final String datastoreRepoName = "datastore/org";
    public static final String datastoreRepoId = "R_datastore";

    public static final long botId = 156364140;
    public static final String botLogin = "commonhaus-bot";
    public static final String botNodeId = "U_kgDOCVHtbA";

    public static final DataLabel APP_NEW = new DataLabel.Builder()
            .name(ApplicationData.NEW).build();
    public static final DataLabel APP_ACCEPTED = new DataLabel.Builder()
            .name(ApplicationData.ACCEPTED).build();
    public static final DataLabel APP_DECLINED = new DataLabel.Builder()
            .id("LA_kwDOKRPTI88AAAABhGp_7g")
            .name(ApplicationData.DECLINED).build();
    public static final Set<DataLabel> APP_LABELS = Set.of(APP_NEW, APP_ACCEPTED, APP_DECLINED);

    @Singleton
    static class AppObjectMapperCustomizer implements ObjectMapperCustomizer {
        public void customize(ObjectMapper mapper) {
            mapper.enable(Feature.IGNORE_UNKNOWN)
                    .setSerializationInclusion(Include.NON_EMPTY)
                    .setVisibility(VisibilityChecker.Std.defaultInstance()
                            .with(JsonAutoDetect.Visibility.ANY));
        }
    }

    protected ContextHelper() {
        super(mock(AppContextService.class), sponsorsInstallationId);
    }

    public void setupInstallationRepositories(GitHubMockSetupContext mocks, AppContextService ctx) throws IOException {
        setLabels(datastoreRepoId, APP_LABELS);

        GitHub s_gh = mocks.installationClient(sponsorsInstallationId);
        ctx.updateConnection(sponsorsInstallationId, s_gh);
        DynamicGraphQLClient s_dql = mocks.installationGraphQLClient(sponsorsInstallationId);
        ctx.updateConnection(sponsorsInstallationId, s_dql);

        setupMockRepository(mocks, s_gh, ctx, "commonhaus/foundation");
        setupMockOrganization(mocks, sponsorsInstallationId, sponsorsOrgName, sponsorsOrgId);

        GHRepository repo = setupMockRepository(mocks, s_gh, ctx, sponsorsRepo, sponsorsRepoId);
        RepositoryDiscoveryEvent repoEvent = new RepositoryDiscoveryEvent(
                DiscoveryAction.ADDED,
                mocks.installationClient(sponsorsInstallationId),
                mocks.installationGraphQLClient(sponsorsInstallationId),
                sponsorsInstallationId,
                repo,
                Optional.ofNullable(null),
                false);

        ctx.repositoryDiscovered(repoEvent);
        ctx.writeToInstallId.put(sponsorsOrgName, sponsorsInstallationId);
        ctx.writeToInstallId.put(sponsorsRepo, sponsorsInstallationId);
        ctx.readToInstallId.put("commonhaus", sponsorsInstallationId);

        GitHub ds_gh = mocks.installationClient(datastoreInstallationId);
        ctx.updateConnection(datastoreInstallationId, ds_gh);
        DynamicGraphQLClient ds_dql = mocks.installationGraphQLClient(datastoreInstallationId);
        ctx.updateConnection(datastoreInstallationId, ds_dql);

        repo = setupMockRepository(mocks, ds_gh, ctx, datastoreRepoName, datastoreRepoId);
        setupMockOrganization(mocks, datastoreInstallationId, datastoreOrgName, datastoreOrganizationId);

        repoEvent = new RepositoryDiscoveryEvent(
                DiscoveryAction.ADDED,
                mocks.installationClient(datastoreInstallationId),
                mocks.installationGraphQLClient(datastoreInstallationId),
                datastoreInstallationId,
                repo,
                Optional.ofNullable(null),
                false);
        ctx.repositoryDiscovered(repoEvent);
        ctx.writeToInstallId.put(datastoreOrgName, datastoreInstallationId);
        ctx.writeToInstallId.put(datastoreRepoName, datastoreInstallationId);

        mocks.configFile(AdminConfigFile.NAME).fromClasspath("/cf-admin.yml");
        AdminConfigFile config = AppContextService.yamlMapper().readValue(
                getClass().getResourceAsStream("/cf-admin.yml"), AdminConfigFile.class);
        ctx.userConfig = config.userManagement();
    }

    public static void setUserManagementConfig(AppContextService ctx)
            throws StreamReadException, DatabindException, IOException {
        AdminConfigFile config = AppContextService.yamlMapper().readValue(
                ContextHelper.class.getResourceAsStream("/cf-admin.yml"), AdminConfigFile.class);
        ctx.userConfig = config.userManagement();
    }

    public static GHUser mockGHUser(String login) {
        final URL url = mock(URL.class);
        lenient().when(url.toString()).thenReturn("");

        GHUser mock = mock(GHUser.class);
        lenient().when(mock.getId()).thenReturn((long) mock.hashCode());
        lenient().when(mock.getNodeId()).thenReturn(login);
        lenient().when(mock.getLogin()).thenReturn(login);
        lenient().when(mock.getHtmlUrl()).thenReturn(url);
        lenient().when(mock.getUrl()).thenReturn(url);
        lenient().when(mock.getAvatarUrl()).thenReturn("");
        return mock;
    }

    public GitHub setupMockTeam(GitHubMockSetupContext mocks) throws IOException {
        GitHub gh = mocks.installationClient(sponsorsInstallationId);
        GHOrganization org = gh.getOrganization(sponsorsOrgName);

        Set<GHUser> testQuorum = new HashSet<>();
        Set<GHUser> council = new HashSet<>();
        Set<GHUser> voting = new HashSet<>();

        for (int i = 1; i < 15; i++) {
            String login = "user" + i;
            GHUser user = mockGHUser(login);
            if (i % 2 == 0) {
                testQuorum.add(user);
            }
            if (i % 3 == 0) {
                council.add(user);
            }
            if (i % 6 == 0) {
                voting.add(user);
            }
            when(gh.getUser(login)).thenReturn(user);
        }

        setupMockTeam(mocks, "team-quorum-default", org, testQuorum);
        setupMockTeam(mocks, "cf-council", org, council);
        setupMockTeam(mocks, "cf-voting", org, voting);

        return gh;
    }

    protected GHRepository setupMockRepository(GitHubMockSetupContext mocks, GitHub gh, AppContextService ctx,
            String repoName)
            throws IOException {
        return setupMockRepository(mocks, gh, ctx, repoName, "R_" + repoName.hashCode());
    }

    protected GHOrganization setupMockOrganization(GitHubMockSetupContext mocks, long installationId, String orgName,
            long orgId) throws IOException {
        GHOrganization org = mocks.ghObject(GHOrganization.class, orgId);
        when(org.getLogin()).thenReturn(orgName);
        when(org.getId()).thenReturn(orgId);

        GitHub gh = mocks.installationClient(installationId);
        when(gh.getOrganization(orgName)).thenReturn(org);
        return org;
    }

    protected GHRepository setupMockRepository(GitHubMockSetupContext mocks, GitHub gh, AppContextService ctx,
            String repoName,
            String nodeId)
            throws IOException {
        GHRepository repo = mocks.repository(repoName);
        when(repo.getFullName()).thenReturn(repoName);
        when(repo.getNodeId()).thenReturn(nodeId);
        when(gh.getRepository(repoName)).thenReturn(repo);

        ctx.refreshScopedQueryContext(sponsorsInstallationId, repo);
        return repo;
    }

    protected GHTeam setupMockTeam(GitHubMockSetupContext mocks, String name, GHOrganization org, Set<GHUser> userSet)
            throws IOException {
        setupMockTeam("commonhaus-test/" + name, userSet);

        GHTeam team = mocks.team(name.hashCode());
        when(team.getMembers()).thenReturn(userSet);
        when(team.getName()).thenReturn(name);
        when(org.getTeamByName(name)).thenReturn(team);

        return team;
    }

    public void setupMockTeam(String fullTeamName, Set<GHUser> users) {
        TEAM_MEMBERS.put(fullTeamName, users);
    }

    public void appendMockTeam(String fullTeamName, GHUser user) {
        Set<GHUser> members = TEAM_MEMBERS.get(fullTeamName);
        members.add(user);
    }

    public void setUserAsUnknown(String login) {
        AdminDataCache.KNOWN_USER.put(login, Boolean.FALSE);
    }

    public void addCollaborator(String repoName, String login) {
        BaseQueryCache.COLLABORATORS.put(repoName, Set.of(login));
    }

    public GitHub setupBotGithub(AppContextService ctx, GitHubMockSetupContext mocks) throws IOException {
        GitHub gh = mocks.installationClient(datastoreInstallationId);

        GHUser bot = mockGHUser(botLogin);
        when(gh.getUser(botLogin)).thenReturn(bot);
        ctx.updateUserConnection(botNodeId, gh);

        ctx.attestationIds.add("member");
        ctx.attestationIds.add("coc");

        return gh;
    }

    public Response mockResponse(String filename) {
        try {
            JsonObject jsonObject = Json.createReader(Files.newInputStream(Path.of(filename))).readObject();

            Response mockResponse = Mockito.mock(Response.class);
            when(mockResponse.getData()).thenReturn(jsonObject);

            return mockResponse;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void mockExistingCommonhausData(GitHub botGithub, AppContextService ctx) throws IOException {
        mockExistingCommonhausData(botGithub, ctx, "src/test/resources/commonhaus-user.yaml");
    }

    public void mockExistingCommonhausData(GitHub botGithub, AppContextService ctx, String filename) throws IOException {
        GHContent content = mock(GHContent.class);
        when(content.read()).thenReturn(Files.newInputStream(Path.of(filename)));
        when(content.getSha()).thenReturn("1234567890abcdef");

        GHRepository dataStore = botGithub.getRepository(datastoreRepoName);
        when(dataStore.getFileContent(anyString())).thenReturn(content);
    }

    public GHContentBuilder mockUpdateCommonhausData(GitHub botGithub, AppContextService ctx) throws IOException {
        GHContentBuilder builder = Mockito.mock(GHContentBuilder.class);
        return mockUpdateCommonhausData(builder, botGithub, ctx, "src/test/resources/commonhaus-user.yaml");
    }

    public GHContentBuilder mockUpdateCommonhausData(GHContentBuilder builder, GitHub botGithub, AppContextService ctx)
            throws IOException {
        return mockUpdateCommonhausData(builder, botGithub, ctx, "src/test/resources/commonhaus-user.yaml");
    }

    public GHContentBuilder mockUpdateCommonhausData(GHContentBuilder builder, GitHub botGithub, AppContextService ctx,
            String filename) throws IOException {
        when(builder.content(anyString())).thenReturn(builder);
        when(builder.message(anyString())).thenReturn(builder);
        when(builder.path(anyString())).thenReturn(builder);
        when(builder.sha(anyString())).thenReturn(builder);

        GHContent responseContent = mock(GHContent.class);
        when(responseContent.read())
                .thenReturn(Files.newInputStream(Path.of(filename)));
        when(responseContent.getSha()).thenReturn("1234567890adefgh");

        GHRepository dataStore = botGithub.getRepository(datastoreRepoName);
        when(dataStore.createContent()).thenReturn(builder);

        GHContentUpdateResponse response = Mockito.mock(GHContentUpdateResponse.class);
        when(response.getContent()).thenReturn(responseContent);

        when(builder.commit()).thenReturn(response);

        return builder;
    }

    public void setLabels(String id, DataLabel... labels) {
        BaseQueryCache.LABELS.computeIfAbsent(id, (k) -> new HashSet<>()).addAll(List.of(labels));
    }

    public void setLabels(String id, Set<DataLabel> labels) {
        BaseQueryCache.LABELS.computeIfAbsent(id, (k) -> new HashSet<>()).addAll(labels);
    }
}
