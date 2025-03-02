package org.commonhaus.automation.github.context;

import static org.commonhaus.automation.github.context.GitHubTeamService.putCachedTeam;
import static org.commonhaus.automation.github.context.GitHubTeamService.putCachedTeamMembers;
import static org.commonhaus.automation.github.context.QueryContext.toOrganizationName;
import static org.commonhaus.automation.github.context.QueryContext.toRelativeName;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.commonhaus.automation.config.EmailNotification;
import org.commonhaus.automation.github.context.QueryContext.GitHubParameterApiCall;
import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.commonhaus.automation.github.queue.PeriodicUpdateQueue;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTeam;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.mockito.Mockito;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonGenerator.Feature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.VisibilityChecker;

import io.quarkiverse.githubapp.testing.dsl.GitHubMockSetupContext;
import io.quarkiverse.githubapp.testing.internal.GitHubAppTestingContext;
import io.quarkus.jackson.ObjectMapperCustomizer;
import io.quarkus.mailer.MockMailbox;
import io.smallrye.graphql.client.Response;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public class ContextHelper {

    // Setup email notification addresses
    static EmailNotification emailNotification = new EmailNotification(
            new String[] { "team-error@example.com" },
            new String[] { "dry-run@example.com" },
            new String[] { "audit@example.com" });

    // These match values in captured Event payloads used as input
    public record DefaultValues(
            long installId,
            long orgId,
            String orgName,
            String repoFullName) {
    }

    public record MockInstallation(
            long installationId,
            GitHub github,
            DynamicGraphQLClient dql,
            GHOrganization organization,
            GHRepository repository,
            QueryContext queryContext) {
    }

    @Singleton
    static class AppObjectMapperCustomizer implements ObjectMapperCustomizer {
        public void customize(ObjectMapper mapper) {
            mapper.enable(Feature.IGNORE_UNKNOWN)
                    .setSerializationInclusion(Include.NON_EMPTY)
                    .setVisibility(VisibilityChecker.Std.defaultInstance()
                            .with(JsonAutoDetect.Visibility.ANY));
        }
    }

    @Inject
    Event<RepositoryDiscoveryEvent> repositoryDiscoveryEvent;

    @Inject
    protected ContextService ctx;

    @Inject
    protected PeriodicUpdateQueue periodicUpdateQueue;

    @Inject
    MockMailbox mailbox;

    GitHubMockSetupContext mocks;
    GitHub github;
    DynamicGraphQLClient dql;

    GHOrganization organization;
    GHRepository repository;
    QueryContext queryContext;

    // Not part of defaults. This is fluid and can be changed.
    boolean defaultDryRun = false;

    public MockInstallation setupCommonMocks(DefaultValues defaults) throws IOException {
        mailbox.clear();
        Stream.of(BaseQueryCache.values()).forEach(v -> v.invalidateAll());
        mocks = GitHubAppTestingContext.get().mocks;

        MockInstallation result = setupAlternateMocks(defaults);

        // establish this as class-level state (default case)
        github = result.github;
        dql = result.dql;
        organization = result.organization;
        repository = result.repository;
        queryContext = result.queryContext;

        return result;
    }

    public MockInstallation setupAlternateMocks(DefaultValues defaults) throws IOException {
        GitHub github = setupMockGitHub(defaults.installId());
        DynamicGraphQLClient dql = setupMockGraphQLClient(defaults.installId());

        GHRepository repository = setupMockRepository(defaults.repoFullName(), github);
        GHOrganization organization = setupMockOrganization(defaults.orgName, defaults.orgId(), github);

        // Setup query context behavior
        QueryContext queryContext = mock(QueryContext.class);
        when(queryContext.getGitHub()).thenReturn(github);
        when(queryContext.getGraphQLClient()).thenReturn(dql);
        when(queryContext.getOrganization(defaults.orgName())).thenReturn(organization);
        when(queryContext.getRepository(defaults.repoFullName())).thenReturn(repository);

        when(queryContext.getLogId()).thenReturn("TEST");
        when(queryContext.isDryRun()).thenReturn(defaultDryRun);
        when(queryContext.getErrorAddresses()).thenReturn(new String[] { "bot-error@example.com" });

        // Pass-through for QueryContext GitHub API calls
        Mockito.doAnswer(invocation -> {
            GitHubParameterApiCall<GHTeam> function = invocation.getArgument(0);
            return function.apply(github, defaultDryRun);
        }).when(queryContext).execGitHubSync(Mockito.any());

        return new MockInstallation(defaults.installId(), github, dql, organization, repository, queryContext);
    }

    public GitHub setupMockGitHub(long installationId) {
        GitHub gh = mocks.installationClient(installationId);
        ctx.updateConnection(installationId, gh);
        return gh;
    }

    public DynamicGraphQLClient setupMockGraphQLClient(long installationId) {
        DynamicGraphQLClient dql = mocks.installationGraphQLClient(installationId);
        ctx.updateConnection(installationId, dql);
        return dql;
    }

    public GHRepository setupMockRepository(String repoName, GitHub gh) throws IOException {
        return setupMockRepository(repoName, "R_" + repoName.hashCode(), gh);
    }

    public GHRepository setupMockRepository(String repoName, String nodeId, GitHub gh) throws IOException {
        String orgName = toOrganizationName(repoName);

        GHRepository repo = mocks.repository(repoName);
        lenient().when(repo.getName()).thenReturn(toRelativeName(orgName, repoName));
        lenient().when(repo.getOwnerName()).thenReturn(orgName);
        lenient().when(repo.getFullName()).thenReturn(repoName);
        lenient().when(repo.getNodeId()).thenReturn(nodeId);

        when(gh.getRepository(repoName)).thenReturn(repo);
        return repo;
    }

    public GHOrganization setupMockOrganization(String orgName, long orgId, GitHub gh) throws IOException {
        return setupMockOrganization(orgName, orgId, "O_" + orgName.hashCode(), gh);
    }

    public GHOrganization setupMockOrganization(String orgName, long orgId, String nodeId, GitHub gh) throws IOException {
        GHOrganization org = mocks.ghObject(GHOrganization.class, orgId);
        lenient().when(org.getLogin()).thenReturn(orgName);
        lenient().when(org.getId()).thenReturn(orgId);
        lenient().when(org.getNodeId()).thenReturn(nodeId);
        when(gh.getOrganization(orgName)).thenReturn(org);
        return org;
    }

    public GHUser setupMockUser(String login, GitHub gh) throws IOException {
        final URL url = new URL("https://github.com/test-stuff");
        GHUser user = mock(GHUser.class);
        lenient().when(user.getLogin()).thenReturn(login);
        lenient().when(user.getId()).thenReturn((long) login.hashCode());
        lenient().when(user.getNodeId()).thenReturn(login);
        lenient().when(user.getHtmlUrl()).thenReturn(url);
        lenient().when(user.getUrl()).thenReturn(url);
        lenient().when(user.getAvatarUrl()).thenReturn("");
        when(gh.getUser(login)).thenReturn(user);
        return user;
    }

    public GHTeam setupMockTeam(String fullName, Set<GHUser> userSet, GitHub gh) throws IOException {
        return setupMockTeam(fullName, userSet, gh, true);
    }

    public GHTeam setupMockTeam(String fullName, Set<GHUser> userSet, GitHub gh, boolean cache) throws IOException {
        String orgName = toOrganizationName(fullName);
        String teamName = toRelativeName(orgName, fullName);

        GHTeam team = mocks.team(fullName.hashCode());
        when(team.getMembers()).thenReturn(userSet);
        when(team.getName()).thenReturn(teamName);

        GHOrganization org = gh.getOrganization(orgName);
        when(org.getTeamByName(teamName)).thenReturn(team);

        if (cache) {
            // preload cache to avoid GH lookup
            putCachedTeamMembers(fullName, userSet);
            putCachedTeam(fullName, team);
        }

        return team;
    }

    public void triggerRepositoryDiscovery(
            DiscoveryAction action,
            MockInstallation mockInstallation,
            boolean bootstrap) {
        triggerRepositoryDiscovery(action,
                mockInstallation,
                mockInstallation.repository(),
                bootstrap);
    }

    public void triggerRepositoryDiscovery(
            DiscoveryAction action,
            MockInstallation mockInstallation,
            GHRepository repo,
            boolean bootstrap) {

        RepositoryDiscoveryEvent repoEvent = new RepositoryDiscoveryEvent(
                action,
                mockInstallation.github(),
                mockInstallation.dql(),
                mockInstallation.installationId(),
                repo,
                bootstrap);
        repositoryDiscoveryEvent.fire(repoEvent);
    }

    public Response setupMockResponse(String filename) {
        try {
            JsonObject jsonObject = Json.createReader(Files.newInputStream(Path.of(filename))).readObject();

            Response mockResponse = Mockito.mock(Response.class);
            when(mockResponse.getData()).thenReturn(jsonObject);
            return mockResponse;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void setupFileContent(MockInstallation mockInstallation,
            String repoPath, Path configFilrPath) throws IOException {
        setupFileContent(mockInstallation, mockInstallation.repository(), repoPath, configFilrPath);
    }

    public void setupFileContent(MockInstallation mockInstallation,
            GHRepository repo, String repoPath, Path configFilrPath) throws IOException {
        GHContent content = mock(GHContent.class);
        when(content.read()).thenReturn(Files.newInputStream(configFilrPath));
        when(repo.getFileContent(repoPath)).thenReturn(content);
    }

    public void setMockLabels(String id, DataLabel... labels) {
        BaseQueryCache.LABELS.computeIfAbsent(id, (k) -> new HashSet<>()).addAll(List.of(labels));
    }

    public void setLabels(String id, Set<DataLabel> labels) {
        BaseQueryCache.LABELS.computeIfAbsent(id, (k) -> new HashSet<>()).addAll(labels);
    }

    // Helper method to load test YAML files
    public <T> T loadYamlResource(String path, Class<T> type) throws IOException {
        return ContextService.yamlMapper.readValue(
                Files.readString(Path.of(path)), type);
    }
}
