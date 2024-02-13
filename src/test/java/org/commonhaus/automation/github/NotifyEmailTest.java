package org.commonhaus.automation.github;

import static io.quarkiverse.githubapp.testing.GitHubAppTesting.given;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.inject.Inject;

import org.commonhaus.automation.github.model.DataLabel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEvent;
import org.kohsuke.github.GHPullRequestFileDetail;
import org.kohsuke.github.PagedIterable;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.graphql.client.Response;

@QuarkusTest
@GitHubAppTest
public class NotifyEmailTest extends GithubTest {
    @Inject
    MockMailbox mailbox;

    @BeforeEach
    void init() {
        mailbox.clear();
    }

    @Test
    void discussionCreated() throws Exception {
        // When a general, not-interesting, discussion is created,
        // - no rules or actions are triggered

        // from src/test/resources/github/eventDiscussionCreated.json
        String discussionId = "D_kwDOLDuJqs4AXaZU";
        verifyNoLabelCache(discussionId);

        Response noLabels = mockResponse(Path.of("src/test/resources/github/queryLabelEmpty.json"));
        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryAppConfig.NAME).fromClasspath("/cf-email.yml");

                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(anyString(), anyMap()))
                            .thenReturn(noLabels);
                })
                .when().payloadFromClasspath("/github/eventDiscussionCreated.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId), times(1))
                            .executeSync(anyString(), anyMap());
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        await().failFast(() -> mailbox.getTotalMessagesSent() != 0)
                .atMost(5, SECONDS);
    }

    @Test
    void discussionCreatedAnnouncements() throws Exception {
        // When a discussion is created in announcements
        // - labels are fetched (label rule)
        // - the notice label is added

        // from src/test/resources/github/eventDiscussionCreatedAnnouncements.json
        String discussionId = "D_kwDOLDuJqs4AXaZM";
        verifyNoLabelCache(discussionId);

        Response repoLabels = mockResponse(Path.of("src/test/resources/github/queryRepositoryLabelsNotice.json"));
        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryAppConfig.NAME).fromClasspath("/cf-email.yml");
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(anyString(), anyMap()))
                            .thenReturn(repoLabels);
                })
                .when().payloadFromClasspath("/github/eventDiscussionCreatedAnnouncements.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    // 1 times: repo labels
                    verify(mocks.installationGraphQLClient(installationId), times(1))
                            .executeSync(anyString(), anyMap());
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() == 1);
    }

    @Test
    void discussionCreatedReviewsLabeled() throws Exception {
        // preload the cache: no request to fetch repo labels (and check our work)
        String discussionId = "D_kwDOLDuJqs4AXaZQ";

        Set<DataLabel> existing = new HashSet<>();
        existing.add(new DataLabel.Builder().name("notice").build());
        QueryHelper.QueryCache.LABELS.putCachedValue(repositoryId, existing);
        verifyLabelCache(repositoryId, 1, List.of("notice"));

        existing = new HashSet<>();
        existing.add(bug);
        QueryHelper.QueryCache.LABELS.putCachedValue(discussionId, existing);
        verifyLabelCache(discussionId, 1, List.of("bug"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryAppConfig.NAME).fromClasspath("/cf-email.yml");
                })
                .when().payloadFromClasspath("/github/eventDiscussionCreatedReviewsLabel.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() == 1);
    }

    @Test
    void discussionLabeled() throws Exception {
        // preload the cache: no request to fetch repo labels (and check our work)
        String discussionId = "D_kwDOLDuJqs4AXNhB";

        Set<DataLabel> existing = new HashSet<>();
        existing.add(new DataLabel.Builder().name("notice").build());
        QueryHelper.QueryCache.LABELS.putCachedValue(repositoryId, existing);
        verifyLabelCache(repositoryId, 1, List.of("notice"));

        existing = new HashSet<>();
        existing.add(notice);
        QueryHelper.QueryCache.LABELS.putCachedValue(discussionId, existing);
        verifyLabelCache(discussionId, 1, List.of("notice"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryAppConfig.NAME).fromClasspath("/cf-email.yml");
                })
                .when().payloadFromClasspath("/github/eventDiscussionLabeled.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        await().failFast(() -> mailbox.getTotalMessagesSent() != 0)
                .atMost(5, SECONDS);
    }

    @Test
    void testRelevantPr() throws Exception {
        String prNodeId = "PR_kwDOLDuJqs5mlMVl";
        verifyNoLabelCache(prNodeId);
        long id = 1721025893;

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryAppConfig.NAME).fromClasspath("/cf-email.yml");
                    // Mocked REST request
                    PagedIterable<GHPullRequestFileDetail> paths = mockPagedIterable(
                            mockGHPullRequestFileDetail("bylaws/README.md"));
                    when(mocks.pullRequest(id).listFiles()).thenReturn(paths);
                })
                .when().payloadFromClasspath("/github/eventPullRequestCreatedBylaws.json")
                .event(GHEvent.PULL_REQUEST)
                .then().github(mocks -> {
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() == 1);
    }
}
