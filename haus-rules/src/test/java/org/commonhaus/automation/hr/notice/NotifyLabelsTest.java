package org.commonhaus.automation.hr.notice;

import static io.quarkiverse.githubapp.testing.GitHubAppTesting.given;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import org.commonhaus.automation.hr.HausRulesTestBase;
import org.commonhaus.automation.hr.config.HausRulesConfig;
import org.commonhaus.automation.hr.rules.RuleHelper;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEvent;
import org.kohsuke.github.GHPullRequestFileDetail;
import org.kohsuke.github.PagedIterable;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.graphql.client.Response;

@QuarkusTest
@GitHubAppTest
public class NotifyLabelsTest extends HausRulesTestBase {

    @Test
    void discussionCreated() throws Exception {
        // When a general discussion is created,
        // - no rules or actions are triggered

        // from src/test/resources/github/eventDiscussionCreated.json
        String discussionId = "D_kwDOLDuJqs4AXteZ";
        verifyNoLabels(discussionId);

        given()
                .github(mocks -> mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-notice-label.yml"))
                .when().payloadFromClasspath("/github/eventDiscussionCreated.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        verifyNoLabels(discussionId);
    }

    @Test
    void discussionCreatedAnnouncements() throws Exception {
        // When a discussion is created in announcements
        // - labels are fetched (label rule): no notice label
        // - the notice label is added

        // from src/test/resources/github/eventDiscussionCreatedAnnouncements.json
        String discussionId = "D_kwDOLDuJqs4AXaZM";

        Response repoLabels = mockResponse("src/test/resources/github/queryRepositoryLabelsNotice.json");
        Response noLabels = mockResponse("src/test/resources/github/queryLabelEmpty.json");
        Response modifiedLabel = mockResponse("src/test/resources/github/mutableAddLabelsToLabelable.json");

        given()
                .github(mocks -> {
                    mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-notice-label.yml");

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

        verifyLabels(discussionId, 1, "notice");
    }

    @Test
    void discussionCreatedReviewsLabeled() throws Exception {
        // When a discussion is created and it already has the
        // notice label, nothing else happens

        // from src/test/resources/github/eventDiscussionCreatedReviewsLabel.json
        String discussionId = "D_kwDOLDuJqs4AXaZQ";

        // preload the cache: no request to fetch repo and discussion labels
        setLabels(repositoryId, NOTICE);
        setLabels(discussionId, NOTICE);

        given()
                .github(mocks -> {
                    mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-notice-label.yml");
                })
                .when().payloadFromClasspath("/github/eventDiscussionCreatedReviewsLabel.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        verifyLabels(discussionId, 1, "notice");
    }

    @Test
    void discussionCreatedOrganizationMember() throws Exception {
        // When a discussion is created in announcements
        // - labels are fetched (label rule): no notice label
        // - organization membership is checked: pass
        // - the notice label is added

        // from src/test/resources/github/eventDiscussionCreatedAnnouncements.json
        String discussionId = "D_kwDOLDuJqs4AXaZM";

        setLabels(repositoryId, NOTICE);
        setLabels(discussionId, BUG);

        Response modifiedLabel = mockResponse(Path.of("src/test/resources/github/mutableAddLabelsToLabelable.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-notice-label-organization.yml");

                    setupGivenMocks(mocks, TEST_ORG);
                    mockTeams(hausMocks);
                    mockAuthor(hausMocks, true, false);

                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("addLabelsToLabelable("), anyMap()))
                            .thenReturn(modifiedLabel);
                })
                .when().payloadFromClasspath("/github/eventDiscussionCreatedAnnouncements.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verifyOrganizationMember(mocks, hausMocks);

                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("addLabelsToLabelable("), anyMap());

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });
        // verify presence of label(s) from mock response in the cache
        verifyLabels(discussionId, 1, "notice");
    }

    @Test
    void discussionCreatedNotOrganizationMember() throws Exception {
        // When a discussion is created in announcements
        // - labels are fetched (label rule): no notice label
        // - organization membership is checked: fail
        // - the notice label is not added

        // from src/test/resources/github/eventDiscussionCreatedAnnouncements.json
        String discussionId = "D_kwDOLDuJqs4AXaZM";

        setLabels(repositoryId, NOTICE);
        setLabels(discussionId, BUG);

        given()
                .github(mocks -> {
                    mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-notice-label-organization.yml");

                    setupGivenMocks(mocks, TEST_ORG);
                    mockTeams(hausMocks);
                    mockAuthor(hausMocks, false, false);
                })
                .when().payloadFromClasspath("/github/eventDiscussionCreatedAnnouncements.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verifyOrganizationMember(mocks, hausMocks);

                    // no call to add labels
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });

        // verify presence of label(s) from mock response in the cache
        verifyLabels(discussionId, 1, "bug");
    }

    @Test
    void discussionCreatedByTeamMember() throws Exception {
        // When a discussion is created in announcements
        // - labels are fetched (label rule): no notice label
        // - team membership is checked: pass
        // - the notice label is added

        String discussionId = "D_kwDOLDuJqs4AXaZM";

        setLabels(repositoryId, NOTICE);
        setLabels(discussionId, BUG);

        Response modifiedLabel = mockResponse(Path.of("src/test/resources/github/mutableAddLabelsToLabelable.Bug.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-notice-label-team.yml");

                    setupGivenMocks(mocks, TEST_ORG);
                    mockTeams(hausMocks);
                    mockAuthor(hausMocks, false, true);

                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("addLabelsToLabelable("), anyMap()))
                            .thenReturn(modifiedLabel);
                })
                .when().payloadFromClasspath("/github/eventDiscussionCreatedAnnouncements.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("addLabelsToLabelable("), anyMap());

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });

        // verify presence of label(s) from mock response in the cache
        verifyLabels(discussionId, 2, "bug", "notice");
    }

    @Test
    void discussionCreatedNotTeamMember() throws Exception {
        // When a discussion is created in announcements
        // - labels are fetched (label rule): no notice label
        // - team membership is checked: fail
        // - the notice label is not added

        // from src/test/resources/github/eventDiscussionCreatedAnnouncements.json
        String discussionId = "D_kwDOLDuJqs4AXaZM";

        setLabels(repositoryId, NOTICE);
        setLabels(discussionId, BUG);

        given()
                .github(mocks -> {
                    mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-notice-label-team.yml");
                    setupGivenMocks(mocks, TEST_ORG);
                    mockTeams(hausMocks);
                    mockAuthor(hausMocks, false, false);
                })
                .when().payloadFromClasspath("/github/eventDiscussionCreatedAnnouncements.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    // no call to add labels
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });

        // verify presence of label(s) from mock response in the cache
        verifyLabels(discussionId, 1, "bug");
    }

    @Test
    void discussionCategoryChangedLabeled() throws Exception {
        // Discussion category changed, add the notice label

        // from src/test/resources/github/eventDiscussionCategoryChanged.json
        String discussionId = "D_kwDOLDuJqs4AXNal";

        // preload the cache: no request to fetch repo and discussion labels
        setLabels(repositoryId, NOTICE);
        setLabels(discussionId, BUG);

        Response modifiedLabel = mockResponse(Path.of("src/test/resources/github/mutableAddLabelsToLabelable.Bug.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-notice-label.yml");

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
                });

        // verify presence of label(s) from mock response in the cache
        verifyLabels(discussionId, 2, "bug", "notice");
    }

    @Test
    void discussionLabeled() throws Exception {
        // When a discussion is labeled, ...
        // from src/test/resources/github/eventDiscussionLabeled.json
        String discussionId = "D_kwDOLDuJqs4AXNhB";

        // preload the cache: no request to fetch labels (and check our work)
        setLabels(discussionId, BUG);
        verifyLabels(discussionId, 1, "bug");

        given()
                .github(mocks -> mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-notice-label.yml"))
                .when().payloadFromClasspath("/github/eventDiscussionLabeled.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        // verify presence of label(s) from mock response in the cache
        verifyLabels(discussionId, 2, "bug", "notice");
    }

    @Test
    void discussionUnlabeledUnknown() throws Exception {
        // When a discussion is unlabeled, cache is cleared (no labels)

        String discussionId = "D_kwDOLDuJqs4AXNhB";
        verifyNoLabels(discussionId);

        given()
                .github(mocks -> mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-notice-label.yml"))
                .when().payloadFromClasspath("/github/eventDiscussionUnlabeled.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        // verify removal of label(s) from the cache
        verifyNoLabels(discussionId);
    }

    @Test
    void discussionUnlabeled() throws Exception {
        // When a discussion is unlabeled, cache is cleared (no labels)
        String discussionId = "D_kwDOLDuJqs4AXNhB";

        // preload the cache: no request to fetch labels (and check our work)
        setLabels(discussionId, BUG);
        verifyLabels(discussionId, 1, "bug");

        given()
                .github(mocks -> {
                    mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-notice-label.yml");
                })
                .when().payloadFromClasspath("/github/eventDiscussionUnlabeled.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });

        // verify removal of label(s) from the cache
        verifyNoLabels(discussionId);
    }

    @Test
    void discussionAddRemoveLabel() throws Exception {
        // Remove a label from a discussion
        String discussionId = "D_kwDOLDuJqs4AXNhB";

        // preload the cache: no request to fetch labels (and check our work)
        setLabels(discussionId, VOTE_OPEN);
        verifyLabels(discussionId, 1, "vote/open");

        setLabels(repositoryId, NOTICE, VOTE_OPEN, VOTE_DONE);
        verifyLabels(repositoryId, 3, "vote/open");

        Response removeLabel = mockResponse(
                Path.of("src/test/resources/github/mutableRemoveLabelsFromLabelable.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-notice-label.yml");

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
                });

        // verify presence of label(s) from mock response in the cache
        verifyLabels(discussionId, 2, "vote/done", "notice");
    }

    @Test
    void testRelevantPr() throws Exception {
        // test query for sender login
        setLogin(null);

        String prNodeId = "PR_kwDOLDuJqs5mlMVl";
        verifyNoLabels(prNodeId);

        long id = 1721025893;
        Response repoLabels = mockResponse(Path.of("src/test/resources/github/queryRepositoryLabelsNotice.json"));
        Response noLabels = mockResponse(Path.of("src/test/resources/github/queryLabelEmpty.json"));
        Response modifiedLabel = mockResponse(Path.of("src/test/resources/github/mutableAddLabelsToLabelable.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-notice-label.yml");
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
                });

        // verify presence of label(s) from mock response in the cache
        verifyLabels(prNodeId, 1, "notice");
        RuleHelper.assertCacheValue("bylaws/*");
    }

    @Test
    public void testLabelChanged() throws Exception {
        String repoId = "R_kgDOLDuJqg";

        // preload the cache: no request to fetch labels (and check our work)
        setLabels(repoId, BUG);
        verifyLabels(repoId, 1, "bug");

        given()
                .github(mocks -> mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-notice-label.yml"))
                .when().payloadFromClasspath("/github/eventLabelCreated.json")
                .event(GHEvent.LABEL)
                .then().github(mocks -> {
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        // verify presence of label(s) in the cache
        verifyLabels(repoId, 2, "bug", "notice");
    }
}
