package org.commonhaus.automation.hk.api;

import static io.quarkiverse.githubapp.testing.GitHubAppTesting.given;
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

import java.nio.file.Path;
import java.util.Set;

import jakarta.inject.Inject;

import org.commonhaus.automation.hk.data.CommonhausUser;
import org.commonhaus.automation.hk.data.MemberStatus;
import org.commonhaus.automation.hk.github.HausKeeperTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHContentBuilder;
import org.kohsuke.github.GHEvent;
import org.kohsuke.github.GHTeam;
import org.kohsuke.github.GHUser;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class ApplicationEventTest extends HausKeeperTestBase {
    String issueId = "I_kwDOL8tG0s6Lx52p";

    @Inject
    ObjectMapper mapper;

    @Override
    @BeforeEach
    protected void init() throws Exception {
        super.init();

        // preload the cache: no request to fetch labels (and check our work)
        setLabels(issueId, Set.of());
    }

    @Test
    void testApplicationComment() throws Exception {
        final GHContentBuilder builder = Mockito.mock(GHContentBuilder.class);

        given()
                .github(mocks -> {
                    setupInstallationRepositories(mocks);
                    setupBotLogin();
                    setupMockTeam();
                    setUserManagementConfig();

                    mockExistingCommonhausData("src/test/resources/haus-member-application.yaml");
                    mockUpdateCommonhausData(builder);
                })
                .when().payloadFromClasspath("/github/eventIssueCommentCreated.json")
                .event(GHEvent.ISSUE_COMMENT)
                .then().github(mocks -> {
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(datastoreInstallationId));
                });

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() > 0);
        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).hasSize(0);
        assertThat(mailbox.getMailsSentTo("repo-errors@example.com")).hasSize(0);
    }

    @Test
    void testApplicationApproved() throws Exception {

        final GHContentBuilder builder = Mockito.mock(GHContentBuilder.class);

        given()
                .github(mocks -> {
                    setupInstallationRepositories(mocks);
                    setupBotLogin();
                    setupMockTeam();
                    setUserManagementConfig();

                    mockExistingCommonhausData("src/test/resources/haus-member-application.yaml");
                    mockUpdateCommonhausData(builder);

                    setupGraphQLProcessing(mocks,
                            QueryResponse.REMOVE_LABELS,
                            QueryResponse.UPDATE_ISSUE);
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
                    GHTeam target = getTeam("commonhaus-test/team-quorum-default");
                    verify(target).add(any(GHUser.class));

                    // 4) Close issue
                    verify(mocks.issue(2345115049L)).close();

                    // 5) verify graphql queries
                    for (String cue : graphQueries) {
                        verify(mocks.installationGraphQLClient(datastoreInstallationId), timeout(500))
                                .executeSync(contains(cue), anyMap());
                    }
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(datastoreInstallationId));
                });

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() > 0);
        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).hasSize(0);
        assertThat(mailbox.getMailsSentTo("repo-errors@example.com")).hasSize(0);
    }

    @Test
    void testApplicationDenied() throws Exception {

        final GHContentBuilder builder = Mockito.mock(GHContentBuilder.class);

        given()
                .github(mocks -> {
                    setupInstallationRepositories(mocks);
                    setupBotLogin();
                    setupMockTeam();
                    setUserManagementConfig();

                    mockExistingCommonhausData("src/test/resources/haus-member-application.yaml");
                    mockUpdateCommonhausData(builder);

                    setupGraphQLProcessing(mocks,
                            QueryResponse.REMOVE_LABELS,
                            QueryResponse.UPDATE_ISSUE);
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

                    // 3) do not add user to target team (0 times)
                    GHTeam target = mocks.team("team-quorum-default".hashCode());
                    verify(target, times(0)).add(any(GHUser.class));

                    // 4) Close issue
                    verify(mocks.issue(2345115049L)).close();

                    // 5) verify graphql queries
                    for (String cue : graphQueries) {
                        verify(mocks.installationGraphQLClient(datastoreInstallationId), timeout(500))
                                .executeSync(contains(cue), anyMap());
                    }
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(datastoreInstallationId));
                });

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() > 0);
        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).hasSize(0);
        assertThat(mailbox.getMailsSentTo("repo-errors@example.com")).hasSize(0);
    }

    enum QueryResponse implements MockResponse {

        REMOVE_LABELS("removeLabelsFromLabelable(",
                "src/test/resources/github/mutableRemoveLabelsFromLabelable.json"),
        UPDATE_ISSUE("updateIssue(input: {",
                "src/test/resources/github/mutableUpdateIssue.json");

        final String cue;
        final Path path;

        QueryResponse(String cue, String path) {
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
}
