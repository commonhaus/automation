package org.commonhaus.automation.admin.github;

import static org.commonhaus.automation.github.context.BaseQueryCache.TEAM_MEMBERS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URL;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import jakarta.inject.Singleton;

import org.commonhaus.automation.admin.AdminDataCache;
import org.commonhaus.automation.github.context.ActionType;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.github.context.QueryContext;
import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
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
import io.quarkus.jackson.ObjectMapperCustomizer;

public class ContextHelper extends QueryContext {

    public static final long installationId = 50263360;
    public static final long organizationId = 144493209;
    public static final String organizationName = "commonhaus-test";

    public static final long botId = 156364140;
    public static final String botLogin = "commonhaus-bot";
    public static final String botNodeId = "U_kgDOCVHtbA";

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
        super(mock(AppContextService.class), installationId);
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
        GHOrganization org = Mockito.mock(GHOrganization.class);
        when(org.getLogin()).thenReturn(organizationName);
        when(org.getId()).thenReturn(organizationId);

        GitHub gh = mocks.installationClient(installationId);
        when(gh.getOrganization(organizationName)).thenReturn(org);
        when(gh.isCredentialValid()).thenReturn(true);

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
            if (i % 4 == 0) {
                voting.add(user);
            }
            when(gh.getUser(login)).thenReturn(user);
        }

        GHUser user = mockGHUser(botLogin);
        when(gh.getUser(botLogin)).thenReturn(user);
        testQuorum.add(user);

        setupMockTeam("team-quorum-default", org, testQuorum);
        setupMockTeam("cf-council", org, council);
        setupMockTeam("cf-voting", org, voting);

        return gh;
    }

    protected GHRepository setupMockRepository(GitHubMockSetupContext mocks, GitHub gh, AppContextService ctx, String repoName)
            throws IOException {
        return setupMockRepository(mocks, gh, ctx, repoName, "R_" + repoName.hashCode());
    }

    protected GHRepository setupMockRepository(GitHubMockSetupContext mocks, GitHub gh, AppContextService ctx, String repoName,
            String nodeId)
            throws IOException {
        GHRepository repo = mocks.repository(repoName);
        when(repo.getFullName()).thenReturn(repoName);
        when(repo.getNodeId()).thenReturn(nodeId);
        when(gh.getRepository(repoName)).thenReturn(repo);

        ctx.refreshScopedQueryContext(gh, repo, installationId);
        return repo;
    }

    protected void setupMockTeam(String name, GHOrganization org, Set<GHUser> userSet) throws IOException {
        TEAM_MEMBERS.put("commonhaus-test/" + name, userSet);

        GHTeam team = Mockito.mock(GHTeam.class);
        when(team.getMembers()).thenReturn(userSet);
        when(team.getName()).thenReturn(name);
        when(org.getTeamByName(name)).thenReturn(team);
    }

    public void setupMockTeam(String teamName, Set<GHUser> users) {
        TEAM_MEMBERS.put(teamName, users);
    }

    public GitHub setupUserGithub(String nodeId) {
        GitHub gh = mock(GitHub.class);
        AdminDataCache.USER_CONNECTION.put(nodeId, gh);
        AdminDataCache.KNOWN_USER.put(botLogin, Boolean.TRUE);
        return gh;
    }

    public void setUserAsUnknown(String login) {
        AdminDataCache.KNOWN_USER.put(login, Boolean.FALSE);
    }

    public GitHub setupBotGithub(AppContextService ctx, GitHubMockSetupContext mocks) throws IOException {
        GitHub gh = mocks.installationClient(installationId);

        GHRepository dataStoreRepo = setupMockRepository(mocks, gh, ctx, ctx.getDataStore(), "R_kgDOL8tG0g");
        RepositoryDiscoveryEvent repoEvent = new RepositoryDiscoveryEvent(
                DiscoveryAction.ADDED, gh, installationId, dataStoreRepo, Optional.ofNullable(null));

        ctx.repositoryDiscovered(repoEvent);

        ctx.attestationIds.add("member");
        ctx.attestationIds.add("coc");

        setupMockRepository(mocks, gh, ctx, "commonhaus-test/sponsors-test");
        return gh;
    }

    @Override
    public String getLogId() {
        throw new UnsupportedOperationException("Unimplemented method 'getLogId'");
    }

    @Override
    public String getRepositoryId() {
        throw new UnsupportedOperationException("Unimplemented method 'getRepositoryId'");
    }

    @Override
    public GHRepository getRepository() {
        throw new UnsupportedOperationException("Unimplemented method 'getRepository'");
    }

    @Override
    public GHOrganization getOrganization() {
        throw new UnsupportedOperationException("Unimplemented method 'getOrganization'");
    }

    @Override
    public EventType getEventType() {
        throw new UnsupportedOperationException("Unimplemented method 'getEventType'");
    }

    @Override
    public ActionType getActionType() {
        throw new UnsupportedOperationException("Unimplemented method 'getActionType'");
    }
}
