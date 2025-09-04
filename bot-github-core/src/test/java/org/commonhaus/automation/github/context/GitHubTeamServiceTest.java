package org.commonhaus.automation.github.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.commonhaus.automation.github.context.GitHubTeamService.getCachedTeam;
import static org.commonhaus.automation.github.context.GitHubTeamService.getCachedTeamMembers;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import org.commonhaus.automation.ContextService;
import org.commonhaus.automation.github.context.DataRepository.Collaborator;
import org.commonhaus.automation.github.context.DataRepository.CollaboratorPermission;
import org.commonhaus.automation.github.context.DataRepository.Collaborators;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHOrganization.RepositoryRole;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTeam;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class GitHubTeamServiceTest extends ContextHelper {

    @Inject
    GitHubTeamService teamService;

    final DefaultValues defaultValues = new DefaultValues(
            46053716,
            new Resource(144493209, "test-org"),
            new Resource("test-org/test-repo"));

    private static final String TEAM_NAME = "test-team";
    private static final String TEAM_FULL_NAME = "test-org/test-team";

    final Set<GHUser> users = new HashSet<>();
    GHTeam team;
    GHOrganization organization;
    GitHubQueryContext queryContext;

    @BeforeEach
    void setup() throws IOException {
        reset();
        users.clear();
        setupDefaultMocks(defaultValues);

        // do not pre-cache team members
        team = mockTeam(TEAM_FULL_NAME, users, hausMocks.github(), false);

        organization = hausMocks.organization();
        queryContext = new ScopedQueryContext(ctx, hausMocks.installationId(), hausMocks.repository())
                .withExisting(hausMocks.github()).withExisting(hausMocks.dql());

        when(ctx.botErrorEmailAddress()).thenReturn(new String[] { "bot-error@example.com" });
        when(ctx.getInstallationClient(hausMocks.installationId())).thenReturn(hausMocks.github());
        when(ctx.getInstallationGraphQLClient(hausMocks.installationId())).thenReturn(hausMocks.dql());

        Mockito.doAnswer(invocation -> {
            return new String[] { "bot-error@example.com", "merged-list@example.com" };
        }).when(ctx).getErrorAddresses(any());
    }

    @Test
    void testGetTeam() throws IOException {
        // Test that getTeam retrieves and caches a team
        GHTeam result = teamService.getTeam(
                queryContext,
                organization,
                TEAM_NAME);

        assertThat(result).isEqualTo(team);
        verify(organization).getTeamByName(TEAM_NAME);

        // Second call should use cached value
        teamService.getTeam(queryContext, organization, TEAM_NAME);
        verify(organization, times(1)).getTeamByName(TEAM_NAME); // Still just one call
    }

    @Test
    void testGetTeamMembers() throws IOException {
        // Setup team members
        users.add(mockUser("user1"));
        users.add(mockUser("user2"));

        // Test getting team members
        Set<GHUser> result = teamService.getTeamMembers(queryContext, TEAM_FULL_NAME);

        assertThat(result).containsExactlyInAnyOrderElementsOf(users);
        verify(team).getMembers();

        // Second call should use cached value
        teamService.getTeamMembers(queryContext, TEAM_FULL_NAME);
        verify(team, times(1)).getMembers(); // Still just one call
    }

    @Test
    void testIsTeamMember() throws IOException {
        // Setup team members
        GHUser user1 = mockUser("user1");
        GHUser user2 = mockUser("user2");
        GHUser user3 = mockUser("user3");

        users.add(user1);
        users.add(user2);

        // Test membership checks
        boolean isMember1 = teamService.isTeamMember(queryContext, user1, TEAM_FULL_NAME);
        boolean isMember3 = teamService.isTeamMember(queryContext, user3, TEAM_FULL_NAME);

        assertThat(isMember1).isTrue();
        assertThat(isMember3).isFalse();
    }

    @Test
    void testAddTeamMember() throws IOException {
        // Setup mocks
        GHUser user = mockUser("newUser");

        // Test adding a team member
        teamService.addTeamMember(queryContext, user, TEAM_FULL_NAME);

        verify(team).add(user);

        // Cache should be invalidated
        assertThat(getCachedTeam(TEAM_FULL_NAME)).isNull();
        assertThat(getCachedTeamMembers(TEAM_FULL_NAME)).isNull();
    }

    @Test
    void testSyncMembers() throws Exception {
        mockUser("user1");
        mockUser("user2");
        mockUser("user3");
        mockUser("user4");
        mockUser("user5");

        // Setup current team members
        Set<String> currentLogins = Set.of("user1", "user2", "user3");

        // Setup expected logins (user2 stays, user1 and user3 should be removed, user4
        // and user5 should be added)
        Set<String> expectedLogins = Set.of("user2", "user4", "user5");

        // Setup GraphQL response for current members
        mockDataTeamQueryResponse(currentLogins);

        // Test syncing members - this should add user4 and user5, and remove user1 and
        // user3
        teamService.syncMembers(queryContext, TEAM_FULL_NAME, expectedLogins, List.of(),
                defaultDryRun, emailNotification);

        if (defaultDryRun) {
            // Verify no changes were made
            verify(team, times(0)).add(any(GHUser.class));
            verify(team, times(0)).remove(any(GHUser.class));
        } else {
            // Verify users were added and removed correctly
            ArgumentCaptor<GHUser> addCaptor = ArgumentCaptor.forClass(GHUser.class);
            ArgumentCaptor<GHUser> removeCaptor = ArgumentCaptor.forClass(GHUser.class);

            verify(team, times(2)).add(addCaptor.capture());
            verify(team, times(2)).remove(removeCaptor.capture());

            List<GHUser> addedUsers = addCaptor.getAllValues();
            List<GHUser> removedUsers = removeCaptor.getAllValues();

            assertThat(addedUsers.stream().map(GHUser::getLogin))
                    .containsExactlyInAnyOrder("user4", "user5");
            assertThat(removedUsers.stream().map(GHUser::getLogin))
                    .containsExactlyInAnyOrder("user1", "user3");
        }

        // Verify an audit email was sent
        verify(ctx).sendEmail(
                anyString(),
                anyString(),
                anyString(),
                eq(defaultDryRun ? emailNotification.dryRun() : emailNotification.audit()));
    }

    @Test
    void testSyncMembersDryRun() throws Exception {
        defaultDryRun = true;
        testSyncMembers();
    }

    @Test
    void testSyncMembersWithIgnoreList() throws Exception {
        mockUser("user1");
        mockUser("user2");
        mockUser("user3");
        mockUser("user4");
        mockUser("user5");
        mockUser("ignore1");

        // Setup current team members
        Set<String> currentLogins = Set.of("user1", "user2", "user3", "ignore1");

        // Setup expected logins - user1 is in ignore list so shouldn't be removed
        Set<String> expectedLogins = Set.of("user2", "user4", "user5");
        List<String> ignoreUsers = List.of("ignore1", "user1");

        // Setup GraphQL response for current members
        mockDataTeamQueryResponse(currentLogins);

        // Test syncing with ignore list
        teamService.syncMembers(queryContext, TEAM_FULL_NAME, expectedLogins, ignoreUsers,
                defaultDryRun, emailNotification);

        // Verify users were added and removed correctly - user1 should NOT be removed
        // because it's in the ignore list
        ArgumentCaptor<GHUser> addCaptor = ArgumentCaptor.forClass(GHUser.class);
        ArgumentCaptor<GHUser> removeCaptor = ArgumentCaptor.forClass(GHUser.class);

        verify(team, times(2)).add(addCaptor.capture());
        verify(team, times(1)).remove(removeCaptor.capture()); // Only user3 should be removed

        List<GHUser> addedUsers = addCaptor.getAllValues();
        List<GHUser> removedUsers = removeCaptor.getAllValues();

        // Check that the right users were added and removed
        assertThat(addedUsers.stream().map(GHUser::getLogin))
                .containsExactlyInAnyOrder("user4", "user5");
        assertThat(removedUsers.stream().map(GHUser::getLogin))
                .containsExactly("user3");
    }

    @Test
    void testSyncMembersWithEmptyTeam() throws Exception {
        mockUser("user1");
        mockUser("user2");

        // Setup current team members
        Set<String> currentLogins = Set.of();

        // Setup expected logins - user1 is in ignore list so shouldn't be removed
        Set<String> expectedLogins = Set.of("user2");
        List<String> ignoreUsers = List.of("user1");

        // Setup GraphQL response for current members
        mockDataTeamQueryResponse(currentLogins);

        // Test syncing with ignore list
        teamService.syncMembers(queryContext, TEAM_FULL_NAME, expectedLogins, ignoreUsers,
                defaultDryRun, emailNotification);

        // Verify users were added and removed correctly - user1 should NOT be removed
        // because it's in the ignore list
        ArgumentCaptor<GHUser> addCaptor = ArgumentCaptor.forClass(GHUser.class);
        ArgumentCaptor<GHUser> removeCaptor = ArgumentCaptor.forClass(GHUser.class);

        verify(team, times(1)).add(addCaptor.capture());
        verify(team, times(0)).remove(removeCaptor.capture()); // Only user3 should be removed

        List<GHUser> addedUsers = addCaptor.getAllValues();
        List<GHUser> removedUsers = removeCaptor.getAllValues();

        // Check that the right users were added and removed
        assertThat(addedUsers.stream().map(GHUser::getLogin))
                .containsExactlyInAnyOrder("user2");
        assertThat(removedUsers.stream().map(GHUser::getLogin))
                .isEmpty();
    }

    @Test
    void testSyncMembersTeamNotFound() throws IOException {
        // GH API will return null for teams that are not found in the organization
        when(organization.getTeamByName(TEAM_NAME)).thenReturn(null);

        teamService.syncMembers(queryContext, TEAM_FULL_NAME, Set.of(), List.of(),
                defaultDryRun, emailNotification);

        ArgumentCaptor<GHUser> addCaptor = ArgumentCaptor.forClass(GHUser.class);
        ArgumentCaptor<GHUser> removeCaptor = ArgumentCaptor.forClass(GHUser.class);

        verify(team, times(0)).add(addCaptor.capture());
        verify(team, times(0)).remove(removeCaptor.capture());
    }

    @Test
    void testSyncMembersHandlesErrors() throws Exception {
        GitHub github = hausMocks.github();

        mockUser("user1");
        mockUser("user2");
        mockUser("user4");
        mockUser("user5");

        GHUser user3 = mockUser("user3");
        when(github.getUser("errorUser")).thenThrow(new GHFileNotFoundException("mock3 user not found"));

        // Setup current team members
        Set<String> currentLogins = Set.of("user1", "user2", "user3");

        // Setup expected logins
        Set<String> expectedLogins = Set.of("user2", "user4", "user5", "errorUser");

        // Setup GraphQL response for current members
        mockDataTeamQueryResponse(currentLogins);

        doThrow(new TestHttpException(401, "mock3 user not found")).when(team).remove(user3);

        // Test syncing members with errors
        teamService.syncMembers(queryContext, TEAM_FULL_NAME, expectedLogins, List.of(),
                defaultDryRun, emailNotification);

        // Verify error emails were sent
        // verify(ctx).sendEmail(
        // anyString(),
        // anyString(),
        // anyString(),
        // eq(emailNotification.audit()));

        verify(ctx).sendEmail(
                anyString(),
                anyString(),
                anyString(),
                argThat(array -> array != null &&
                        Arrays.asList(array).containsAll(List.of("bot-error@example.com", "team-error@example.com"))));
    }

    @Test
    void testSyncCollaborators() throws Exception {
        GHRepository repository = hausMocks.repository();

        // Mock repository and users
        mockUser("user1");
        mockUser("user2");
        mockUser("user3");
        mockUser("user4");
        mockUser("user5");

        Set<Collaborator> testCollaborators = Set.of(
                // user1: Team + Direct access (mixed - manageable)
                new Collaborator("user1", "WRITE", List.of(
                        new CollaboratorPermission("READ", "Organization"),
                        new CollaboratorPermission("WRITE", "Repository"))),

                // user2: Team-only access (protected from removal)
                new Collaborator("user2", "READ", List.of(
                        new CollaboratorPermission("READ", "Organization"))),

                // user3: Direct-only access (fully manageable, will be removed)
                new Collaborator("user3", "WRITE", List.of(
                        new CollaboratorPermission("WRITE", "Repository"))),

                // user5: expected collaborator
                new Collaborator("user5", "WRITE", List.of(
                        new CollaboratorPermission("WRITE", "Repository"))));

        // Logins received as members of team
        Set<String> teamLogins = Set.of("user4", "user5");

        mockDataCollaboratorsQueryResponse(new Collaborators(testCollaborators));

        GHOrganization.RepositoryRole collaboratorRole = GHOrganization.RepositoryRole
                .from(GHOrganization.Permission.PUSH);

        // Test syncing collaborators
        teamService.syncCollaborators(queryContext, repository, collaboratorRole,
                teamLogins, List.of(), defaultDryRun, emailNotification);

        // Expected changes:
        // - user1: Mixed access (team and org) -> Remove repo access (not in expected)
        // - user2: Org only -> protected, org-level access cannot be removed
        // - user3: Repo only → Remove direct access (not in expected)
        // - user4: Not current, but in expected → Add direct access
        // - user5: Current collaborator also in expected → untouched

        if (defaultDryRun) {
            // Verify no changes were made
            verify(repository, times(0)).removeCollaborators(anyList());
            verify(repository, times(0)).addCollaborators(anyList(), any(RepositoryRole.class));
        } else {
            // Verify users were added and removed correctly

            // Capture removed users
            ArgumentCaptor<List<GHUser>> removeCaptor = ArgumentCaptor.captor();
            verify(repository, times(1)).removeCollaborators(removeCaptor.capture());

            // Capture added users
            ArgumentCaptor<List<GHUser>> addCaptor = ArgumentCaptor.captor();
            verify(repository, times(1)).addCollaborators(addCaptor.capture(), any(RepositoryRole.class));

            // Check the right users were added and removed
            List<GHUser> addedUsers = addCaptor.getValue();
            List<GHUser> removedUsers = removeCaptor.getValue();

            assertThat(addedUsers.stream().map(GHUser::getLogin))
                    .containsExactlyInAnyOrder("user4");
            // user1 is an admin, and should not be removed
            assertThat(removedUsers.stream().map(GHUser::getLogin))
                    .containsExactlyInAnyOrder("user1", "user3");
        }

        // Verify an audit email was sent
        verify(ctx).sendEmail(
                anyString(),
                anyString(),
                anyString(),
                eq(defaultDryRun ? emailNotification.dryRun() : emailNotification.audit()));
    }

    @Test
    void testSyncCollaboratorsDryRun() throws Exception {
        defaultDryRun = true;
        testSyncCollaborators();
    }

    // Helper methods

    private void mockDataTeamQueryResponse(Set<String> logins) throws ExecutionException, InterruptedException {
        JsonObject jsonObject = Json.createObjectBuilder()
                .add("organization", Json.createObjectBuilder()
                        .add("team", Json.createObjectBuilder()
                                .add("members", Json.createObjectBuilder()
                                        .add("nodes", Json.createArrayBuilder(
                                                logins.stream()
                                                        .map(login -> Json.createObjectBuilder().add("login", login))
                                                        .toList()))
                                        .add("pageInfo", Json.createObjectBuilder()
                                                .add("endCursor", "Y3Vyc29yOnYyOpHOAAxXCQ==")
                                                .add("hasNextPage", false)))))
                .build();

        mockResponse("team(slug: $slug)", jsonObject);
    }

    private void mockDataCollaboratorsQueryResponse(Collaborators collaborators)
            throws ExecutionException, InterruptedException {
        JsonObject jsonObject = Json.createObjectBuilder()
                .add("repository", Json.createObjectBuilder()
                        .add("collaborators", Json.createObjectBuilder()
                                .add("edges", collaboratorsToJson(collaborators))
                                .add("pageInfo", Json.createObjectBuilder()
                                        .add("endCursor", "Y3Vyc29yOnYyOpHOAAxXCQ==")
                                        .add("hasNextPage", false))))
                .build();

        mockResponse("collaborators(first: 100, after: $after)", jsonObject);
    }

    private JsonArray collaboratorsToJson(Collaborators collaborators) {
        return Json.createArrayBuilder(
                collaborators.members().stream()
                        .map(c -> Json.createObjectBuilder()
                                .add("node", Json.createObjectBuilder()
                                        .add("login", c.login()))
                                .add("permission", c.permission())
                                .add("permissionSources", Json.createArrayBuilder(
                                        c.permissionSources().stream()
                                                .map(ps -> Json.createObjectBuilder()
                                                        .add("source", Json.createObjectBuilder()
                                                                .add("__typename", ps.permissionSourceType()))
                                                        .add("permission", ps.permission()))
                                                .collect(Collectors.toList()))))
                        .toList())
                .build();
    }

    final static class MockContextService {

        final ContextService ctx = Mockito.mock(ContextService.class);

        @Produces
        ContextService produceContextService() {
            return ctx;
        }
    }
}
