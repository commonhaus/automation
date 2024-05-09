package org.commonhaus.automation.github.notice;

import static io.quarkiverse.githubapp.testing.GitHubAppTesting.given;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import org.commonhaus.automation.RepositoryConfigFile;
import org.commonhaus.automation.github.rules.RuleHelper;
import org.commonhaus.automation.github.test.ContextHelper;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEvent;
import org.kohsuke.github.GHPullRequestFileDetail;
import org.kohsuke.github.PagedIterable;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.graphql.client.Response;

@QuarkusTest
@GitHubAppTest
public class NotifyLabelsTest extends ContextHelper {

    @Test
    void discussionCreated() throws Exception {
        // When a general discussion is created,
        // - no rules or actions are triggered

        // from src/test/resources/github/eventDiscussionCreated.json
        String discussionId = "D_kwDOLDuJqs4AXaZU";
        verifyNoLabelCache(discussionId);

        given()
                .github(mocks -> mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-notice-label.yml"))
                .when().payloadFromClasspath("/github/eventDiscussionCreated.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        verifyNoLabelCache(discussionId);
    }

    @Test
    void discussionCreatedAnnouncements() throws Exception {
        // When a discussion is created in announcements
        // - labels are fetched (label rule): no notice label
        // - the notice label is added

        // from src/test/resources/github/eventDiscussionCreatedAnnouncements.json
        String discussionId = "D_kwDOLDuJqs4AXaZM";

        Response repoLabels = mockResponse(Path.of("src/test/resources/github/queryRepositoryLabelsNotice.json"));
        Response noLabels = mockResponse(Path.of("src/test/resources/github/queryLabelEmpty.json"));
        Response modifiedLabel = mockResponse(Path.of("src/test/resources/github/mutableAddLabelsToLabelable.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-notice-label.yml");

                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("repository(owner: $owner, name: $name) {"), anyMap()))
                            .thenReturn(repoLabels);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("... on Labelable {"), anyMap()))
                            .thenReturn(noLabels);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("addLabelsToLabelable("), anyMap()))
                            .thenReturn(modifiedLabel);
                })
                .when().payloadFromClasspath("/github/eventDiscussionCreatedAnnouncements.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("... on Labelable {"), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("repository(owner: $owner, name: $name) {"), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("addLabelsToLabelable("), anyMap());

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        verifyLabelCache(discussionId, 1, List.of("notice"));
    }

    @Test
    void discussionCreatedReviewsLabeled() throws Exception {
        // When a discussion is created and it already has the
        // notice label, nothing else happens

        // from src/test/resources/github/eventDiscussionCreatedReviewsLabel.json
        String discussionId = "D_kwDOLDuJqs4AXaZQ";

        // preload the cache: no request to fetch repo and discussion labels
        setLabels(repositoryId, notice);
        setLabels(discussionId, notice);

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-notice-label.yml");
                })
                .when().payloadFromClasspath("/github/eventDiscussionCreatedReviewsLabel.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        verifyLabelCache(discussionId, 1, List.of("notice"));
    }

    @Test
    void discussionCategoryChangedLabeled() throws Exception {
        // When a discussion is created and it already has the
        // notice label, nothing else happens

        // from src/test/resources/github/eventDiscussionCategoryChanged.json
        String discussionId = "D_kwDOLDuJqs4AXNal";

        // preload the cache: no request to fetch repo and discussion labels
        setLabels(repositoryId, notice);
        setLabels(discussionId, bug);

        Response modifiedLabel = mockResponse(Path.of("src/test/resources/github/mutableAddLabelsToLabelable.Bug.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-notice-label.yml");

                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("addLabelsToLabelable("), anyMap()))
                            .thenReturn(modifiedLabel);
                })
                .when().payloadFromClasspath("/github/eventDiscussionCategoryChanged.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("addLabelsToLabelable("), anyMap());

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        verifyLabelCache(discussionId, 2, List.of("bug", "notice"));
    }

    @Test
    void discussionLabeled() throws Exception {
        // When a discussion is labeled, ...
        // from src/test/resources/github/eventDiscussionLabeled.json
        String discussionId = "D_kwDOLDuJqs4AXNhB";

        // preload the cache: no request to fetch labels (and check our work)
        setLabels(discussionId, bug);
        verifyLabelCache(discussionId, 1, List.of("bug"));

        given()
                .github(mocks -> mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-notice-label.yml"))
                .when().payloadFromClasspath("/github/eventDiscussionLabeled.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        verifyLabelCache(discussionId, 2, List.of("bug", "notice"));
    }

    @Test
    void discussionUnlabeledUnknown() throws Exception {
        // When a discussion is unlabeled, ...
        // from src/test/resources/github/eventDiscussionUnlabeled.json
        String discussionId = "D_kwDOLDuJqs4AXNhB";
        verifyNoLabelCache(discussionId);

        given()
                .github(mocks -> mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-notice-label.yml"))
                .when().payloadFromClasspath("/github/eventDiscussionUnlabeled.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId), times(0))
                            .executeSync(anyString(), anyMap());
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        verifyNoLabelCache(discussionId);
    }

    @Test
    void discussionUnlabeled() throws Exception {
        // When a discussion is unlabeled, ...
        // from src/test/resources/github/eventDiscussionUnlabeled.json
        String discussionId = "D_kwDOLDuJqs4AXNhB";

        // preload the cache: no request to fetch labels (and check our work)
        setLabels(discussionId, bug);
        verifyLabelCache(discussionId, 1, List.of("bug"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-notice-label.yml");
                })
                .when().payloadFromClasspath("/github/eventDiscussionUnlabeled.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId), times(0))
                            .executeSync(anyString(), anyMap());
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        verifyLabelCache(discussionId, 0, List.of());
    }

    @Test
    void discussionAddRemoveLabel() throws Exception {
        // When a discussion is unlabeled, ...
        // from src/test/resources/github/eventDiscussionUnlabeled.json
        String discussionId = "D_kwDOLDuJqs4AXNhB";

        // preload the cache: no request to fetch labels (and check our work)
        setLabels(discussionId, VOTE_OPEN);
        verifyLabelCache(discussionId, 1, List.of("vote/open"));

        setLabels(repositoryId, notice, VOTE_OPEN, VOTE_DONE);
        verifyLabelCache(repositoryId, 3, List.of("vote/open"));

        Response removeLabel = mockResponse(
                Path.of("src/test/resources/github/mutableRemoveLabelsFromLabelable.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-notice-label.yml");

                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("removeLabelsFromLabelable("), anyMap()))
                            .thenReturn(removeLabel);
                })
                .when().payloadFromClasspath("/github/eventDiscussionLabeledVoteDone.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("removeLabelsFromLabelable("), anyMap());

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        verifyLabelCache(discussionId, 2, List.of("vote/done", "notice"));
    }

    @Test
    void testRelevantPr() throws Exception {
        // test query for sender login
        setLogin(null);

        String prNodeId = "PR_kwDOLDuJqs5mlMVl";
        verifyNoLabelCache(prNodeId);

        long id = 1721025893;
        Response repoLabels = mockResponse(Path.of("src/test/resources/github/queryRepositoryLabelsNotice.json"));
        Response noLabels = mockResponse(Path.of("src/test/resources/github/queryLabelEmpty.json"));
        Response modifiedLabel = mockResponse(Path.of("src/test/resources/github/mutableAddLabelsToLabelable.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-notice-label.yml");
                    // 2 GraphQL queries to fetch labels
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("... on Labelable {"), anyMap()))
                            .thenReturn(noLabels);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("repository(owner: $owner, name: $name) {"), anyMap()))
                            .thenReturn(repoLabels);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("addLabelsToLabelable("), anyMap()))
                            .thenReturn(modifiedLabel);

                    // Mocked REST request
                    PagedIterable<GHPullRequestFileDetail> paths = mockPagedIterable(
                            mockGHPullRequestFileDetail("bylaws/README.md"));
                    when(mocks.pullRequest(id).listFiles()).thenReturn(paths);
                })
                .when().payloadFromClasspath("/github/eventPullRequestOpenedBylaws.json")
                .event(GHEvent.PULL_REQUEST)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("... on Labelable {"), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("repository(owner: $owner, name: $name) {"), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("addLabelsToLabelable("), anyMap());
                    verify(mocks.pullRequest(id)).listFiles();

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        verifyLabelCache(prNodeId, 1, List.of("notice"));
        RuleHelper.assertCacheValue("bylaws/*");
    }

    @Test
    public void testLabelChanged() throws Exception {
        String repoId = "R_kgDOLDuJqg";

        // preload the cache: no request to fetch labels (and check our work)
        setLabels(repoId, bug);
        verifyLabelCache(repoId, 1, List.of("bug"));

        given()
                .github(mocks -> mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-notice-label.yml"))
                .when().payloadFromClasspath("/github/eventLabelCreated.json")
                .event(GHEvent.LABEL)
                .then().github(mocks -> {
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        verifyLabelCache(repoId, 2, List.of("bug", "notice"));
    }
}
