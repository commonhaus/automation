package org.commonhaus.automation.github.context;

import static org.commonhaus.automation.github.context.GitHubTeamService.getCachedTeamMembers;
import static org.commonhaus.automation.github.context.GitHubTeamService.putCachedTeam;
import static org.commonhaus.automation.github.context.GitHubTeamService.putCachedTeamMembers;
import static org.commonhaus.automation.github.context.QueryContext.toOrganizationName;
import static org.commonhaus.automation.github.context.QueryContext.toRelativeName;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
import org.kohsuke.github.GHPullRequestFileDetail;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTeam;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterable;
import org.kohsuke.github.PagedIterator;
import org.kohsuke.github.PagedSearchIterable;
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
    public static EmailNotification emailNotification = new EmailNotification(
            new String[] { "team-error@example.com" },
            new String[] { "dry-run@example.com" },
            new String[] { "audit@example.com" });

    public record Resource(
            long id,
            String node_id,
            String fullName) {

        public Resource(String fullName) {
            this(fullName.hashCode(), fullName);
        }

        public Resource(long id, String fullName) {
            this(id, "X_" + fullName.hashCode(), fullName);
        }

        public Resource(String nodeId, String fullName) {
            this(fullName.hashCode(), nodeId, fullName);
        }
    }

    // These match values in captured Event payloads used as input
    public record DefaultValues(
            long installId,
            Resource organization,
            Resource repository,
            boolean mockQueryContext) {

        public DefaultValues(long installId, Resource organization, Resource repository) {
            this(installId, organization, repository, true);
        }

        public long orgId() {
            return organization.id();
        }

        public String orgNodeId() {
            return organization.node_id();
        }

        public String orgName() {
            return organization.fullName();
        }

        public String repoNodeId() {
            return repository.node_id();
        }

        public String repoFullName() {
            return repository.fullName();
        }
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
    public Event<RepositoryDiscoveryEvent> fireRepositoryDiscoveryEvent;

    @Inject
    public ContextService ctx;

    @Inject
    public PeriodicUpdateQueue periodicQueue;

    @Inject
    public MockMailbox mailbox;

    public GitHubMockSetupContext mocks;

    Map<String, Boolean> visited = new HashMap<>();
    public List<String> graphQueries = new ArrayList<>();

    // Installation created by common mocks
    public MockInstallation hausMocks;

    // Not part of defaults. This is fluid and can be changed.
    public boolean defaultDryRun = false;

    public void reset() {
        mailbox.clear();
        visited.clear();
        graphQueries.clear();
        Stream.of(BaseQueryCache.values()).forEach(v -> v.invalidateAll());
    }

    public MockInstallation setupGivenMocks(GitHubMockSetupContext mocks, DefaultValues defaultValues) throws IOException {
        this.mocks = mocks;
        return setupDefaultMocks(defaultValues);
    }

    public MockInstallation setupDefaultMocks(DefaultValues defaultValues) throws IOException {
        this.hausMocks = setupCommonMocks(defaultValues);
        return hausMocks;
    }

    public MockInstallation setupCommonMocks(DefaultValues defaults) throws IOException {
        if (mocks == null) {
            mocks = GitHubAppTestingContext.get().mocks;
        }
        return setupInstallationMocks(defaults);
    }

    public MockInstallation setupInstallationMocks(DefaultValues defaults) throws IOException {
        GitHub github = mockGitHub(defaults.installId());
        DynamicGraphQLClient dql = mockGraphQLClient(defaults.installId());

        GHRepository repository = mockRepository(defaults, github);
        GHOrganization organization = mockOrganization(defaults, github);

        // Setup query context behavior
        QueryContext queryContext = null;
        if (defaults.mockQueryContext) {
            queryContext = mock(QueryContext.class);
            when(queryContext.getGitHub()).thenReturn(github);
            when(queryContext.getGraphQLClient()).thenReturn(dql);
            when(queryContext.getOrganization(defaults.orgName())).thenReturn(organization);
            when(queryContext.getRepository(defaults.repoFullName())).thenReturn(repository);

            when(queryContext.getLogId()).thenReturn("TEST");
            when(queryContext.isDryRun()).thenReturn(defaultDryRun);
            when(queryContext.getErrorAddresses()).thenReturn(new String[] { "bot-error@example.com" });

            Mockito.doAnswer(invocation -> {
                return new String[] { "bot-error@example.com", "merged-list@example.com" };
            }).when(queryContext).getErrorAddresses(any());

            // Pass-through for QueryContext GitHub API calls
            Mockito.doAnswer(invocation -> {
                GitHubParameterApiCall<GHTeam> function = invocation.getArgument(0);
                return function.apply(github, defaultDryRun);
            }).when(queryContext).execGitHubSync(Mockito.any());
        }

        return new MockInstallation(defaults.installId(), github, dql, organization, repository, queryContext);
    }

    public GitHub mockGitHub(long installationId) {
        GitHub gh = mocks.installationClient(installationId);
        String key = "installation-" + installationId;
        if (visited.containsKey(key)) {
            return gh;
        }
        visited.put(key, true);
        when(gh.isCredentialValid()).thenReturn(true);
        ctx.updateConnection(installationId, gh);
        return gh;
    }

    public DynamicGraphQLClient mockGraphQLClient(long installationId) {
        DynamicGraphQLClient dql = mocks.installationGraphQLClient(installationId);
        ctx.updateConnection(installationId, dql);
        return dql;
    }

    public GHRepository mockRepository(DefaultValues defaults, GitHub gh) throws IOException {
        return mockRepository(defaults.repoFullName(), defaults.repoNodeId(), gh);
    }

    public GHRepository mockRepository(String repoFullName, GitHub gh) throws IOException {
        return mockRepository(repoFullName, null, gh);
    }

    public GHRepository mockRepository(String repoFullName, String nodeId, GitHub gh) throws IOException {
        if (visited.containsKey(repoFullName)) {
            return gh.getRepository(repoFullName);
        }
        visited.put(repoFullName, true);

        if (nodeId == null) {
            nodeId = "R_" + repoFullName.hashCode();
        }
        String orgName = toOrganizationName(repoFullName);

        GHRepository repo = mocks.repository(repoFullName);
        lenient().when(repo.getName()).thenReturn(toRelativeName(orgName, repoFullName));
        lenient().when(repo.getOwnerName()).thenReturn(orgName);
        lenient().when(repo.getFullName()).thenReturn(repoFullName);
        lenient().when(repo.getNodeId()).thenReturn(nodeId);
        lenient().when(gh.getRepository(repoFullName)).thenReturn(repo);
        return repo;
    }

    public GHOrganization mockOrganization(DefaultValues defaults, GitHub gh) throws IOException {
        return mockOrganization(defaults.orgName(), defaults.orgId(), defaults.orgNodeId(), gh);
    }

    public GHOrganization mockOrganization(MockInstallation install, String orgName) throws IOException {
        return mockOrganization(orgName, 0, null, install.github());
    }

    public GHOrganization mockOrganization(String orgName, GitHub gh) throws IOException {
        return mockOrganization(orgName, 0, null, gh);
    }

    public GHOrganization mockOrganization(String orgName, long orgId, GitHub gh) throws IOException {
        return mockOrganization(orgName, orgId, null, gh);
    }

    public GHOrganization mockOrganization(String orgName, long orgId, String nodeId, GitHub gh) throws IOException {
        if (visited.containsKey(orgName)) {
            return gh.getOrganization(orgName);
        }
        visited.put(orgName, true);

        if (nodeId == null) {
            nodeId = "O_" + orgName.hashCode();
        }
        if (orgId == 0) {
            orgId = orgName.hashCode();
        }

        GHOrganization org = mocks.ghObject(GHOrganization.class, orgId);
        lenient().when(org.getLogin()).thenReturn(orgName);
        lenient().when(org.getId()).thenReturn(orgId);
        lenient().when(org.getNodeId()).thenReturn(nodeId);
        when(gh.getOrganization(orgName)).thenReturn(org);
        return org;
    }

    public static GHPullRequestFileDetail mockGHPullRequestFileDetail(String filename) {
        GHPullRequestFileDetail mock = mock(GHPullRequestFileDetail.class);
        when(mock.getFilename()).thenReturn(filename);
        return mock;
    }

    public GHUser mockUser(String login) throws IOException {
        return mockUser(login, hausMocks.github());
    }

    public GHUser mockUser(String login, GitHub gh) throws IOException {
        if (visited.containsKey(login)) {
            return gh.getUser(login);
        }
        visited.put(login, true);

        final URL url = new URL("https://github.com/test-stuff");
        GHUser user = mock(GHUser.class);
        lenient().when(user.getLogin()).thenReturn(login);
        lenient().when(user.getId()).thenReturn((long) login.hashCode());
        lenient().when(user.getNodeId()).thenReturn(login);
        lenient().when(user.getHtmlUrl()).thenReturn(url);
        lenient().when(user.getUrl()).thenReturn(url);
        lenient().when(user.getAvatarUrl()).thenReturn("");
        lenient().when(gh.getUser(login)).thenReturn(user);
        return user;
    }

    public GHTeam mockTeam(String fullName, Set<GHUser> userSet) throws IOException {
        return mockTeam(fullName, userSet, hausMocks.github(), true);
    }

    public GHTeam mockTeam(MockInstallation installation, String fullName, Set<GHUser> userSet) throws IOException {
        return mockTeam(fullName, userSet, installation.github, true);
    }

    public GHTeam mockTeam(String fullName, Set<GHUser> userSet, GitHub gh, boolean cache) throws IOException {
        String orgName = toOrganizationName(fullName);
        GHOrganization org = gh.getOrganization(orgName);
        String teamName = toRelativeName(orgName, fullName);

        final GHTeam team;
        if (visited.containsKey(teamName)) {
            team = org.getTeamByName(teamName);
        } else {
            long id = fullName.hashCode();
            team = mocks.team(id);
            when(org.getTeamByName(teamName)).thenReturn(team);
            when(team.getName()).thenReturn(teamName);
            when(team.getId()).thenReturn(id);
            visited.put(teamName, true);
        }

        when(team.getMembers()).thenReturn(userSet);
        if (cache) {
            // preload cache to avoid GH lookup
            putCachedTeamMembers(fullName, userSet);
            putCachedTeam(fullName, team);
        }
        return team;
    }

    public GHTeam getTeam(String fullTeamName) {
        long id = fullTeamName.hashCode();
        return mocks.team(id);
    }

    public void appendCachedTeam(String fullTeamName, GHUser user) {
        Set<GHUser> members = getCachedTeamMembers(fullTeamName);
        members.add(user);
    }

    public Response mockResponse(String filename) {
        return mockResponse(Path.of(filename));
    }

    public Response mockResponse(Path filePath) {
        try {
            JsonObject jsonObject = Json
                    .createReader(Files.newInputStream(filePath))
                    .readObject();
            Response mockResponse = Mockito.mock(Response.class);
            when(mockResponse.getData()).thenReturn(jsonObject);
            return mockResponse;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void mockFileContent(MockInstallation mockInstallation,
            String repoPath, String contentFilePath) throws IOException {
        mockFileContent(mockInstallation, repoPath, Path.of(contentFilePath));
    }

    public void mockFileContent(MockInstallation mockInstallation,
            String repoPath, Path contentFilePath) throws IOException {
        mockFileContent(mockInstallation.repository(), repoPath, contentFilePath);
    }

    public void mockFileContent(GHRepository repo, String repoPath, String contentFilePath) throws IOException {
        mockFileContent(repo, repoPath, Path.of(contentFilePath));
    }

    public void mockFileContent(GHRepository repo, String repoPath, Path contentFilePath) throws IOException {
        GHContent content = mock(GHContent.class);
        when(content.read()).thenReturn(Files.newInputStream(contentFilePath));
        when(repo.getFileContent(repoPath)).thenReturn(content);
    }

    public void setLabels(String id, DataLabel... labels) {
        BaseQueryCache.LABELS.computeIfAbsent(id, (k) -> new HashSet<>()).addAll(List.of(labels));
    }

    public void setLabels(String id, Set<DataLabel> labels) {
        BaseQueryCache.LABELS.computeIfAbsent(id, (k) -> new HashSet<>()).addAll(labels);
    }

    @SafeVarargs
    @SuppressWarnings("unchecked")
    public static <T> PagedIterable<T> mockPagedIterable(T... contentMocks) {
        PagedIterable<T> iterableMock = mock(PagedIterable.class);
        try {
            lenient().when(iterableMock.toList()).thenAnswer(ignored2 -> List.of(contentMocks));
        } catch (IOException e) {
            // This should never happen
            // That's a classic unwise comment, but it's a mock, so surely we're safe? :)
            throw new TestRuntimeException("Error with mockPagedIterable", e);
        }
        lenient().when(iterableMock.iterator()).thenAnswer(ignored -> {
            PagedIterator<T> iteratorMock = mock(PagedIterator.class);
            Iterator<T> actualIterator = List.of(contentMocks).iterator();
            when(iteratorMock.next()).thenAnswer(ignored2 -> actualIterator.next());
            lenient().when(iteratorMock.hasNext()).thenAnswer(ignored2 -> actualIterator.hasNext());

            return iteratorMock;
        });
        return iterableMock;
    }

    @SafeVarargs
    @SuppressWarnings("unchecked")
    public static <T> PagedSearchIterable<T> mockPagedSearchIterable(T... contentMocks) {
        PagedSearchIterable<T> iterableMock = mock(PagedSearchIterable.class);
        try {
            lenient().when(iterableMock.toList()).thenAnswer(ignored2 -> List.of(contentMocks));
        } catch (IOException e) {
            // This should never happen
            throw new TestRuntimeException("Error with mockPagedSearchIterable", e);
        }
        lenient().when(iterableMock.iterator()).thenAnswer(ignored -> {
            PagedIterator<T> iteratorMock = mock(PagedIterator.class);
            Iterator<T> actualIterator = List.of(contentMocks).iterator();
            when(iteratorMock.next()).thenAnswer(ignored2 -> actualIterator.next());
            lenient().when(iteratorMock.hasNext()).thenAnswer(ignored2 -> actualIterator.hasNext());

            return iteratorMock;
        });
        return iterableMock;
    }

    public BotComment createBotComment(String nodeId, DataCommonComment comment) {
        BotComment botComment = new BotComment(nodeId, comment);
        BaseQueryCache.RECENT_BOT_CONTENT.put(nodeId, botComment);
        return botComment;
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
        fireRepositoryDiscoveryEvent.fire(repoEvent);
    }

    // Helper method to load test YAML files
    public <T> T loadYamlResource(String path, Class<T> type) throws IOException {
        return ContextService.yamlMapper.readValue(
                Files.readString(Path.of(path)), type);
    }

    public interface MockResponse {
        String cue();

        Path path();

        long installationId();
    }

    public void setupGraphQLProcessing(GitHubMockSetupContext mocks, MockResponse... responses) throws Exception {
        for (MockResponse response : responses) {
            String cue = response.cue();

            graphQueries.add(cue);

            long installationId = response.installationId();
            Response mockResponse = mockResponse(response.path());
            when(mocks.installationGraphQLClient(installationId)
                    .executeSync(contains(cue), anyMap()))
                    .thenReturn(mockResponse);
        }
    }

    public void setupGraphQLProcessing(MockInstallation mocks, MockResponse... responses) throws Exception {
        for (MockResponse response : responses) {
            String cue = response.cue();

            graphQueries.add(cue);

            Response mockResponse = mockResponse(response.path());
            when(mocks.dql()
                    .executeSync(contains(cue), anyMap()))
                    .thenReturn(mockResponse);
        }
    }
}
