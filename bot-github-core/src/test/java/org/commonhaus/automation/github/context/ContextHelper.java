package org.commonhaus.automation.github.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
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

import io.quarkiverse.githubapp.testing.GitHubAppTesting;
import io.quarkiverse.githubapp.testing.dsl.GitHubMockSetupContext;
import io.quarkiverse.githubapp.testing.internal.GitHubAppTestingContext;
import io.quarkus.jackson.ObjectMapperCustomizer;
import io.quarkus.mailer.MockMailbox;
import io.smallrye.graphql.client.GraphQLError;
import io.smallrye.graphql.client.Response;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

/**
 * Common test context setup and helper methods for GitHub App testing.
 * <p>
 * Your GitHubApp test should extend this class:
 *
 * <pre>
 * {@literal @}QuarkusTest
 * {@literal @}GitHubAppTest
 * public class MyGitHubAppTest extends ContextHelper {
 *    // Your test code here
 * }
 * </pre>
 * <p>
 * Use {@link #setupDefaultMocks(DefaultValues)}, {@link #setupCommonMocks(DefaultValues)},
 * {@link #setupGivenMocks(GitHubMockSetupContext, DefaultValues)},
 * or {@link #setupInstallationMocks(DefaultValues)} to create a {@link MockInstallation} with a GitHub client, GraphQL client,
 * organization,
 * and repository that all correctly reference each other. A generic {@link QueryContext} can also be mocked and associated with
 * the installation.
 * <ul>
 * <li>{@link #setupGivenMocks(GitHubMockSetupContext, DefaultValues)} will set the {@link #mocks} field with the provided mocks
 * (use with {@link GitHubAppTesting#given()}).
 * <li>{@link #setupDefaultMocks(DefaultValues)} will set the {@link #hausMocks} field with the created mocks.
 * <li>{@link #setupCommonMocks(DefaultValues)} will set the {@link #mocks} field with the common mocks from the
 * {@link GitHubAppTestingContext}
 * </ul>
 * <p>
 * Note that when running the bot live, each GitHub organization (roughly) corresponds to a unique installation ID.
 * Using MockInstallations allows you to emulate this behavior in tests (where access is not guaranteed across installations).
 * <p>
 * Additional methods:
 * <ul>
 * <li>{@link #reset()} to clear the mailbox, visited resources, and graph queries.
 * <li>{@link #mockUser(String)} or {@link #mockUser(String, GitHub)} to create mock users.
 * <li>{@link #mockTeam(String, Set)} or {@link #mockTeam(MockInstallation, String, Set)} to create mock teams.
 * <li>{@link #getTeam(String)} to get a mock team.
 * <li>{@link #appendCachedTeam(String, GHUser)} to append a user to a cached team.
 * <li>{@link #mockFileContent(GHRepository, String, Path)} or {@link #mockFileContent(MockInstallation, String, Path)} to mock
 * file content.
 * <li>{@link #loadYamlResource(String, Class)} to load test YAML files.
 * <li>{@link #setLabels(String, DataLabel...)} to set labels for a resource.
 * <li>{@link #createBotComment(String, DataCommonComment)} to create a bot comment.
 * <li>{@link #mockResponse(String)} or {@link #mockResponse(Path)} to create a mock GraphQL response.
 * <li>{@link #mockPagedIterable(Object...)} or {@link #mockPagedSearchIterable(Object...)} to create mock paged iterables.
 * <li>{@link #triggerRepositoryDiscovery(DiscoveryAction, MockInstallation, boolean)} to trigger repository discovery.
 * <li>Use {@link #emailNotification} for common email notification addresses.
 * <li>Use {@link #ctx} to access the injected ContextService.
 * <li>Use {@link #updateQueue} to access the injected periodic update queue.
 * <li>Use {@link #hausMocks} to access the default installation mocks.
 * <li>Use {@link #mailbox} to access the mock mailbox.
 * <li>Use {@link #mocks} to access the GitHubAppTestingContext.
 * <li>Use {@link #defaultDryRun} to set the default dry run behavior.
 * </ul>
 * <p>
 * Note: If a GitHub mock is not specified, it will use the mock from the {@link #hausMocks} field.
 * <p>
 * To simplify verification of GraphQL methods:
 * <ul>
 * <li>Implement the {@link MockResponse} interface with a cue, path, and installation ID.
 * <li>Use {@link #setupGraphQLProcessing(GitHubMockSetupContext, MockResponse...)} or
 * {@link #setupGraphQLProcessing(MockInstallation, MockResponse...)} to setup GraphQL processing.
 *
 * <pre>
 * // in this example, we're using an enum to implement MockResponse
 * setupGraphQLProcessing(mocks,
 *         MockResponseImpl.REMOVE_LABELS,
 *         MockResponseImpl.UPDATE_ISSUE);
 * </pre>
 *
 * <li>{@link #graphQueries} will contain the cues that will match each GraphQL invocation.
 * <li>Verify that graphQL methods were called based on the cues stored in {@link #graphQueries}.
 *
 * <pre>
 * for (String cue : graphQueries) {
 *     verify(mocks.installationGraphQLClient(datastoreInstallationId), timeout(500))
 *             .executeSync(contains(cue), anyMap());
 * }
 * verifyNoMoreInteractions(mocks.installationGraphQLClient(datastoreInstallationId));
 * </pre>
 *
 * </li>
 * </ul>
 */
public class ContextHelper {

    // Setup email notification addresses
    public static final EmailNotification emailNotification = new EmailNotification(
            new String[] { "team-error@example.com" },
            new String[] { "dry-run@example.com" },
            new String[] { "audit@example.com" });

    /**
     * Define a "resource": a unique identifier for a GitHub object.
     * This is used to create unique identifiers for organizations, repositories, etc.
     * The id and node_id will be generated from the fullName if not otherwise provided.
     */
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

    /**
     * Define an "installation": the values that should be used to create
     * an installation, organization, repository and (optionally) a query context
     * that all cross-reference each other.
     */
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

    /**
     * A MockInstallation: the installationId with associated mock GitHub and GraphQL clients;
     * and an associated mock organization, repository, and (optional) query context
     */
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
    public PeriodicUpdateQueue updateQueue;

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

    public void assertNoErrorEmails() {
        await()
                .atMost(1, TimeUnit.SECONDS)
                .failFast("You've got mail:\n" + mailbox.getTotalMessagesSent(), () -> mailbox.getTotalMessagesSent() > 0)
                .until(() -> mailbox.getTotalMessagesSent() == 0);

        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).hasSize(0);
        assertThat(mailbox.getMailsSentTo("repo-errors@example.com")).hasSize(0);
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

    /**
     * Create a mock GraphQL client for an installation.
     *
     * @param installationId
     * @return
     */
    public DynamicGraphQLClient mockGraphQLClient(long installationId) {
        DynamicGraphQLClient dql = mocks.installationGraphQLClient(installationId);
        ctx.updateConnection(installationId, dql);
        return dql;
    }

    /**
     * Uses the repository name from DefaultValues and GitHub client
     *
     * @see #mockRepository(String, String, GitHub)
     */
    public GHRepository mockRepository(DefaultValues defaults, GitHub gh) throws IOException {
        return mockRepository(defaults.repoFullName(), defaults.repoNodeId(), gh);
    }

    /**
     * Uses the provided repository name and GitHub client
     *
     * @see #mockRepository(String, String, GitHub)
     */
    public GHRepository mockRepository(String repoFullName, GitHub gh) throws IOException {
        return mockRepository(repoFullName, null, gh);
    }

    /**
     * Create a mock repository
     *
     * @param repoFullName Repository full name (required)
     * @param nodeId Repository node_id (optional; computed from repoFullName if not provided)
     * @param gh GitHub client
     * @return
     * @throws IOException
     */
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

    /**
     * Uses the organization name, id, and node_id from provided default values
     *
     * @see #mockOrganization(String, long, String, GitHub)
     */
    public GHOrganization mockOrganization(DefaultValues defaults, GitHub gh) throws IOException {
        return mockOrganization(defaults.orgName(), defaults.orgId(), defaults.orgNodeId(), gh);
    }

    /**
     * Uses the provded orgName, and the GitHub client from the installation.
     *
     * @see #mockOrganization(String, long, String, GitHub)
     */
    public GHOrganization mockOrganization(MockInstallation install, String orgName) throws IOException {
        return mockOrganization(orgName, 0, null, install.github());
    }

    /**
     * Uses the provded orgName and github client.
     *
     * @see #mockOrganization(String, long, String, GitHub)
     */
    public GHOrganization mockOrganization(String orgName, GitHub gh) throws IOException {
        return mockOrganization(orgName, 0, null, gh);
    }

    /**
     * Uses the provded orgName, orgId, and GitHub client
     *
     * @see #mockOrganization(String, long, String, GitHub)
     */
    public GHOrganization mockOrganization(String orgName, long orgId, GitHub gh) throws IOException {
        return mockOrganization(orgName, orgId, null, gh);
    }

    /**
     * Create a mock organization.
     *
     * @param orgName Organization name (required)
     * @param orgId Organization id (optional; computed from orgName if not provided)
     * @param nodeId Organization node_id (optional; computed from orgName if not provided)
     * @param gh GitHub client
     * @return
     * @throws IOException
     */
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

    /**
     * Mock a user.
     * Uses the {@link #hausMocks} GitHub client.
     *
     * @see #mockUser(String, GitHub)
     */
    public GHUser mockUser(String login) throws IOException {
        return mockUser(login, hausMocks.github());
    }

    public GHUser mockUser(String login, GitHub gh) throws IOException {
        return mockUser(login, login.hashCode(), login, gh);
    }

    public GHUser mockUser(String login, long id, GitHub gh) throws IOException {
        return mockUser(login, id, login, gh);
    }

    /**
     * Mock a user.
     * (login, id, node_id, html_url, url, avatar_url)
     *
     * @param login
     * @param gh
     * @return
     * @throws IOException
     */
    public GHUser mockUser(String login, long id, String nodeId, GitHub gh) throws IOException {
        if (visited.containsKey(login)) {
            return gh.getUser(login);
        }
        visited.put(login, true);

        final URL url = new URL("https://github.com/test-stuff");
        GHUser user = mock(GHUser.class);
        lenient().when(user.getLogin()).thenReturn(login);
        lenient().when(user.getId()).thenReturn(id);
        lenient().when(user.getNodeId()).thenReturn(nodeId);
        lenient().when(user.getHtmlUrl()).thenReturn(url);
        lenient().when(user.getUrl()).thenReturn(url);
        lenient().when(user.getAvatarUrl()).thenReturn("");
        lenient().when(gh.getUser(login)).thenReturn(user);
        return user;
    }

    /**
     * Mock a team with a set of users.
     * Uses the {@link #hausMocks} GitHub client.
     *
     * @see #mockTeam(String, Set, GitHub, boolean)
     */
    public GHTeam mockTeam(String fullName, Set<GHUser> userSet) throws IOException {
        return mockTeam(fullName, userSet, hausMocks.github(), true);
    }

    /**
     * Mock a team with a set of users.
     * Uses the GitHub client from the installation.
     *
     * @see #mockTeam(String, Set, GitHub, boolean)
     */
    public GHTeam mockTeam(MockInstallation installation, String fullName, Set<GHUser> userSet) throws IOException {
        return mockTeam(fullName, userSet, installation.github, true);
    }

    /**
     * Mock a team with a set of users.
     * If cache is true, the team and members will be added to the cache.
     *
     * @param fullName
     * @param userSet
     * @param gh
     * @param cache
     * @return
     * @throws IOException
     */
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

    /**
     * @param fullTeamName
     * @return the mock for a team based on its name.
     */
    public GHTeam getTeam(String fullTeamName) {
        long id = fullTeamName.hashCode();
        return mocks.team(id);
    }

    /**
     * Append a mock user to a pre-cached team.
     *
     * @param fullTeamName
     * @param user
     */
    public void appendCachedTeam(String fullTeamName, GHUser user) {
        Set<GHUser> members = getCachedTeamMembers(fullTeamName);
        members.add(user);
    }

    /**
     * @see #mockResponse(Path)
     */
    public Response mockResponse(String filename) {
        return mockResponse(Path.of(filename));
    }

    /**
     * Create a mock GraphQL response from a file.
     *
     * @param filePath
     * @return
     */
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

    public Response mockGraphQLNotFound(MockInstallation mocks, String cue) throws ExecutionException, InterruptedException {
        GraphQLError error = mock(GraphQLError.class);
        when(error.getMessage()).thenReturn("Not Found");
        when(error.getOtherFields()).thenReturn(Map.of("type", "NOT_FOUND"));

        Response mockResponse = mock(Response.class);
        when(mockResponse.hasError()).thenReturn(true);
        when(mockResponse.getErrors()).thenReturn(List.of(error));
        when(mocks.dql()
                .executeSync(contains(cue), anyMap()))
                .thenReturn(mockResponse);
        return mockResponse;
    }

    public void mockGraphQLException(MockInstallation mocks, String cue, Exception e)
            throws ExecutionException, InterruptedException {
        ;
        when(mocks.dql()
                .executeSync(contains(cue), anyMap()))
                .thenThrow(e);
    }

    /**
     * Mock file content for a repository.
     *
     * @see #mockFileContent(MockInstallation, String, Path)
     */
    public void mockFileContent(MockInstallation mockInstallation,
            String repoPath, String contentFilePath) throws IOException {
        mockFileContent(mockInstallation, repoPath, Path.of(contentFilePath));
    }

    /**
     * Mock file content for a repository.
     * The repository is assumed to be the repository associated with the installation.
     *
     * @see #mockFileContent(GHRepository, String, Path)
     */
    public void mockFileContent(MockInstallation mockInstallation,
            String repoPath, Path contentFilePath) throws IOException {
        mockFileContent(mockInstallation.repository(), repoPath, contentFilePath);
    }

    /**
     * Mock file content for a repository.
     *
     * @see #mockFileContent(GHRepository, String, Path)
     */
    public void mockFileContent(GHRepository repo, String repoPath, String contentFilePath) throws IOException {
        mockFileContent(repo, repoPath, Path.of(contentFilePath));
    }

    /**
     * Mock file content for a repository:
     * Mocks GHContent.read() to return the file content.
     * repo.getFileContent(repoPath) will return the GHContent.
     *
     * @param repo Mock GHRepository
     * @param repoPath Path to file in repository
     * @param contentFilePath Path to local file content
     * @throws IOException
     */
    public void mockFileContent(GHRepository repo, String repoPath, Path contentFilePath) throws IOException {
        GHContent content = mock(GHContent.class);
        when(content.read()).thenReturn(Files.newInputStream(contentFilePath));
        when(repo.getFileContent(repoPath)).thenReturn(content);
    }

    /**
     * Mock a pull request file detail.
     *
     * @param filename
     * @return
     */
    public static GHPullRequestFileDetail mockGHPullRequestFileDetail(String filename) {
        GHPullRequestFileDetail mock = mock(GHPullRequestFileDetail.class);
        when(mock.getFilename()).thenReturn(filename);
        return mock;
    }

    /**
     * Pre-set labels into the cache
     *
     * @param id
     * @param labels
     */
    public void setLabels(String id, DataLabel... labels) {
        BaseQueryCache.LABELS.computeIfAbsent(id, (k) -> new HashSet<>()).addAll(List.of(labels));
    }

    /**
     * Pre-set labels into the cache
     *
     * @param id
     * @param labels
     */
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

    /**
     * Trigger a repository discovery event.
     *
     * @param action
     * @param mockInstallation
     * @param bootstrap
     */
    public void triggerRepositoryDiscovery(
            DiscoveryAction action,
            MockInstallation mockInstallation,
            boolean bootstrap) {
        triggerRepositoryDiscovery(action,
                mockInstallation,
                mockInstallation.repository(),
                bootstrap);
    }

    /**
     * Trigger a repository discovery event.
     *
     * @param action
     * @param mockInstallation
     * @param repo
     * @param bootstrap
     */
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

    /**
     * Load a YAML resource.
     *
     * @param <T>
     * @param path
     * @param type
     * @return
     * @throws IOException
     */
    public <T> T loadYamlResource(String path, Class<T> type) throws IOException {
        return ContextService.yamlMapper.readValue(
                Files.readString(Path.of(path)), type);
    }

    /**
     * MockResponse interface for GraphQL processing.
     */
    public interface MockResponse {
        /** Partial string that should match the outgoing query */
        String cue();

        /** Local path to file containing test query result */
        Path path();

        /** Installation id (used to locate the correct mock client) */
        long installationId();
    }

    /**
     * Setup GraphQL processing for a list of responses.
     *
     * @param mocks the GitHubMockSetupContext; the GraphQL will be looked up using the installation ID of the response
     * @param responses the list of responses
     * @throws Exception
     */
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

    /**
     * Setup GraphQL processing for a list of responses.
     *
     * @param mocks the MockInstallation (the GraphQL from this installation will be used)
     * @param responses the list of responses
     * @throws Exception
     */
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

    public void verifyGraphQLProcessing(MockInstallation mocks, boolean only) throws Exception {
        for (String cue : graphQueries) {
            verify(mocks.dql(), timeout(500))
                    .executeSync(contains(cue), anyMap());
        }
        if (only) {
            verifyNoMoreInteractions(mocks.dql());
        }
    }
}
