package org.commonhaus.automation.admin.api;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.commonhaus.automation.admin.AdminDataCache;
import org.commonhaus.automation.admin.data.CommonhausUser;
import org.commonhaus.automation.admin.data.MemberStatus;
import org.commonhaus.automation.admin.github.AppContextService;
import org.commonhaus.automation.admin.github.ContextHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHContentBuilder;
import org.kohsuke.github.GHEvent;
import org.kohsuke.github.GHTeam;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkiverse.githubapp.testing.GitHubAppTesting;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.graphql.client.Response;

@QuarkusTest
@GitHubAppTest
public class ApplicationEventTest extends ContextHelper {

    @Inject
    MockMailbox mailbox;

    @Inject
    AppContextService ctx;

    @Inject
    ObjectMapper mapper;

    @BeforeEach
    void init() throws IOException {
        mailbox.clear();
        Stream.of(AdminDataCache.values()).forEach(AdminDataCache::invalidateAll);

        // When a discussion is labeled, ...
        // from src/test/resources/github/eventIssueLabeled-accepted.json
        String issueId = "I_kwDOL8tG0s6Lx52p";

        // preload the cache: no request to fetch labels (and check our work)
        setLabels(issueId, Set.of());
    }

    @Test
    void testApplicationComment() throws Exception {
        final GHContentBuilder builder = Mockito.mock(GHContentBuilder.class);

        GitHubAppTesting.given()
                .github(mocks -> {
                    setupInstallationRepositories(mocks, ctx);

                    GitHub botGithub = setupBotGithub(ctx, mocks);
                    when(botGithub.isCredentialValid()).thenReturn(true);

                    setupMockTeam(mocks);
                    mockExistingCommonhausData(botGithub, ctx, "src/test/resources/haus-member-application.yaml");

                    mockUpdateCommonhausData(builder, botGithub, ctx);
                })
                .when().payloadFromClasspath("/github/eventIssueCommentCreated.json")
                .event(GHEvent.ISSUE_COMMENT)
                .then().github(mocks -> {

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(datastoreInstallationId));
                });

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() == 1);
        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).hasSize(0);
        assertThat(mailbox.getMailsSentTo("repo-errors@example.com")).hasSize(0);
    }

    @Test
    void testApplicationApproved() throws Exception {

        Response removeLabel = mockResponse("src/test/resources/github/mutableRemoveLabelsFromLabelable.json");
        Response updateIssue = mockResponse("src/test/resources/github/mutableUpdateIssue.json");

        final GHContentBuilder builder = Mockito.mock(GHContentBuilder.class);

        GitHubAppTesting.given()
                .github(mocks -> {
                    setupInstallationRepositories(mocks, ctx);

                    GitHub botGithub = setupBotGithub(ctx, mocks);
                    when(botGithub.isCredentialValid()).thenReturn(true);

                    setupMockTeam(mocks);
                    mockExistingCommonhausData(botGithub, ctx, "src/test/resources/haus-member-application.yaml");

                    mockUpdateCommonhausData(builder, botGithub, ctx);

                    when(mocks.installationGraphQLClient(datastoreInstallationId)
                            .executeSync(contains("removeLabelsFromLabelable("), anyMap()))
                            .thenReturn(removeLabel);

                    when(mocks.installationGraphQLClient(datastoreInstallationId)
                            .executeSync(contains("updateIssue(input: {"), anyMap()))
                            .thenReturn(updateIssue);
                })
                .when().payloadFromClasspath("/github/eventIssueLabeled-accepted.json")
                .event(GHEvent.ISSUES)
                .then().github(mocks -> {

                    // 1) Set member flag, move status from UNKNOWN -> ACTIVE
                    // 2) remove application
                    final ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
                    verify(builder).content(contentCaptor.capture());
                    var result = ctx.yamlMapper().readValue(contentCaptor.getValue(), CommonhausUser.class);

                    assertThat(result.application()).isNull();
                    assertThat(result.isMember()).isTrue();
                    assertThat(result.status()).isEqualTo(MemberStatus.ACTIVE); // changed from UNKNOWN -> PENDING

                    // 3) add user to target team
                    GHTeam target = mocks.team("team-quorum-default".hashCode());
                    verify(target).add(any(GHUser.class));

                    // 4) Close issue
                    verify(mocks.issue(2345115049L)).close();

                    // 5) remove application/new
                    verify(mocks.installationGraphQLClient(datastoreInstallationId), timeout(500))
                            .executeSync(contains("updateIssue(input: {"), anyMap());
                    verify(mocks.installationGraphQLClient(datastoreInstallationId), timeout(500))
                            .executeSync(contains("removeLabelsFromLabelable("), anyMap());

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(datastoreInstallationId));
                });

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() == 1);
        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).hasSize(0);
        assertThat(mailbox.getMailsSentTo("repo-errors@example.com")).hasSize(0);
    }

    @Test
    void testApplicationDenied() throws Exception {

        Response removeLabel = mockResponse("src/test/resources/github/mutableRemoveLabelsFromLabelable.json");
        Response updateIssue = mockResponse("src/test/resources/github/mutableUpdateIssue.json");

        final GHContentBuilder builder = Mockito.mock(GHContentBuilder.class);

        GitHubAppTesting.given()
                .github(mocks -> {
                    setupInstallationRepositories(mocks, ctx);

                    GitHub botGithub = setupBotGithub(ctx, mocks);
                    when(botGithub.isCredentialValid()).thenReturn(true);

                    setupMockTeam(mocks);
                    mockExistingCommonhausData(botGithub, ctx, "src/test/resources/haus-member-application.yaml");

                    mockUpdateCommonhausData(builder, botGithub, ctx);

                    when(mocks.installationGraphQLClient(datastoreInstallationId)
                            .executeSync(contains("removeLabelsFromLabelable("), anyMap()))
                            .thenReturn(removeLabel);
                    when(mocks.installationGraphQLClient(datastoreInstallationId)
                            .executeSync(contains("updateIssue(input: {"), anyMap()))
                            .thenReturn(updateIssue);
                })
                .when().payloadFromClasspath("/github/eventIssueLabeled-declined.json")
                .event(GHEvent.ISSUES)
                .then().github(mocks -> {
                    // 1) Set member flag, move status from UNKNOWN -> DECLINED
                    final ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
                    verify(builder).content(contentCaptor.capture());
                    var result = ctx.yamlMapper().readValue(contentCaptor.getValue(), CommonhausUser.class);

                    assertThat(result.application()).isNull();
                    assertThat(result.isMember()).isFalse();
                    assertThat(result.status()).isEqualTo(MemberStatus.DECLINED); // changed from UNKNOWN -> PENDING

                    // 3) add user to target team
                    GHTeam target = mocks.team("team-quorum-default".hashCode());
                    verify(target, times(0)).add(any(GHUser.class));

                    // 4) Close issue
                    verify(mocks.issue(2345115049L)).close();

                    // 5) remove application/new
                    verify(mocks.installationGraphQLClient(datastoreInstallationId), timeout(500))
                            .executeSync(contains("updateIssue(input: {"), anyMap());
                    verify(mocks.installationGraphQLClient(datastoreInstallationId), timeout(500))
                            .executeSync(contains("removeLabelsFromLabelable("), anyMap());

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(datastoreInstallationId));
                });

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() == 1);
        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).hasSize(0);
        assertThat(mailbox.getMailsSentTo("repo-errors@example.com")).hasSize(0);
    }
}
