package org.commonhaus.automation.github;

import static io.quarkiverse.githubapp.testing.GitHubAppTesting.given;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.commonhaus.automation.github.model.DataLabel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEvent;
import org.kohsuke.github.GHPullRequestFileDetail;
import org.kohsuke.github.PagedIterable;
import org.kohsuke.github.PagedIterator;
import org.mockito.Mockito;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.graphql.client.Response;

@QuarkusTest
@GitHubAppTest
public class NotifyLabelsTest {
    final String repoFullName = "commonhaus/automation-test";
    final String repositoryId = "R_kgDOLDuJqg";
    final long repoId = 742099370;
    final long installationId = 46053716;

    static final DataLabel bug = new DataLabel.Builder().name("bug").id("LA_kwDOLDuJqs8AAAABfqsdNQ").build();
    static final DataLabel notice = new DataLabel.Builder().name("notice").id("LA_kwDOLDuJqs8AAAABgn2hGA").build();

    Response mockResponse(Path filename) {
        try {
            JsonObject jsonObject = Json.createReader(Files.newInputStream(filename)).readObject();

            Response mockResponse = Mockito.mock(Response.class);
            when(mockResponse.getData()).thenReturn(jsonObject);

            return mockResponse;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void beforeEach() {
        QueryHelper.QueryCache.LABELS.invalidateAll();
        verifyNoLabelCache(repositoryId);
        Arc.container().instance(QueryHelper.class).get().setBotSenderLogin("login");
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
                    mocks.configFile(RepositoryAppConfig.NAME).fromClasspath("/cf-automation.yml");

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

        verifyLabelCache(discussionId, 0, List.of());
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
        Response noLabels = mockResponse(Path.of("src/test/resources/github/queryLabelEmpty.json"));
        Response modifiedLabel = mockResponse(Path.of("src/test/resources/github/addLabelsToLabelableResponse.json"));
        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryAppConfig.NAME).fromClasspath("/cf-label.yml");
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(anyString(), anyMap()))
                            .thenReturn(noLabels, repoLabels, modifiedLabel);
                })
                .when().payloadFromClasspath("/github/eventDiscussionCreatedAnnouncements.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    // 3 times: item labels, repo labels, add new label
                    verify(mocks.installationGraphQLClient(installationId), times(3))
                            .executeSync(anyString(), anyMap());
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        verifyLabelCache(discussionId, 1, List.of("notice"));
    }

    @Test
    void discussionCreatedReviewsLabeled() throws Exception {
        // When a discussion is created in announcements
        // - labels are fetched (label rule)
        // - the notice label is added
        // - email is sent
        // - a workflow is dispatched

        // from src/test/resources/github/eventDiscussionCreatedReviewsLabel.json
        String discussionId = "D_kwDOLDuJqs4AXaZQ";

        // preload the cache: no request to fetch repo labels (and check our work)
        Set<DataLabel> existing = new HashSet<>();
        existing.add(new DataLabel.Builder().name("notice").build());
        QueryHelper.QueryCache.LABELS.putCachedValue(repositoryId, existing);
        verifyLabelCache(repositoryId, 1, List.of("notice"));

        Response bugLabel = mockResponse(Path.of("src/test/resources/github/queryLabelBug.json"));
        Response modifiedLabel = mockResponse(Path.of("src/test/resources/github/addLabelsToLabelableResponseBug.json"));
        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryAppConfig.NAME).fromClasspath("/cf-label.yml");
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(anyString(), anyMap()))
                            .thenReturn(bugLabel, modifiedLabel);
                })
                .when().payloadFromClasspath("/github/eventDiscussionCreatedReviewsLabel.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    // 2 times: item labels, repo labels (cached), add new label
                    verify(mocks.installationGraphQLClient(installationId), times(2))
                            .executeSync(anyString(), anyMap());
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
        Set<DataLabel> existing = new HashSet<>();
        existing.add(bug);
        QueryHelper.QueryCache.LABELS.putCachedValue(discussionId, existing);
        verifyLabelCache(discussionId, 1, List.of("bug"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryAppConfig.NAME).fromClasspath("/cf-label.yml");
                })
                .when().payloadFromClasspath("/github/eventDiscussionLabeled.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        verifyLabelCache(discussionId, 2, List.of("bug", "duplicate"));
    }

    @Test
    void discussionUnlabeledUnknown() throws Exception {
        // When a discussion is unlabeled, ...
        // from src/test/resources/github/eventDiscussionUnlabeled.json
        String discussionId = "D_kwDOLDuJqs4AXNhB";
        verifyNoLabelCache(discussionId);

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryAppConfig.NAME).fromClasspath("/cf-label.yml");
                })
                .when().payloadFromClasspath("/github/eventDiscussionUnlabeled.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId), times(0))
                            .executeSync(anyString(), anyMap());
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
        Set<DataLabel> existing = new HashSet<>();
        existing.add(bug);
        QueryHelper.QueryCache.LABELS.putCachedValue(discussionId, existing);
        verifyLabelCache(discussionId, 1, List.of("bug"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryAppConfig.NAME).fromClasspath("/cf-label.yml");
                })
                .when().payloadFromClasspath("/github/eventDiscussionUnlabeled.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId), times(0))
                            .executeSync(anyString(), anyMap());
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        verifyLabelCache(discussionId, 0, List.of());
    }

    @Test
    void testRelevantPr() throws Exception {
        // test query for sender login
        Arc.container().instance(QueryHelper.class).get().setBotSenderLogin(null);

        String prNodeId = "PR_kwDOLDuJqs5mlMVl";
        verifyNoLabelCache(prNodeId);

        long id = 1721025893;
        Response viewer = mockResponse(Path.of("src/test/resources/github/queryViewer.json"));
        Response repoLabels = mockResponse(Path.of("src/test/resources/github/queryRepositoryLabelsNotice.json"));
        Response noLabels = mockResponse(Path.of("src/test/resources/github/queryLabelEmpty.json"));
        Response modifiedLabel = mockResponse(Path.of("src/test/resources/github/addLabelsToLabelableResponse.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryAppConfig.NAME).fromClasspath("/cf-label.yml");
                    // 2 GraphQL queries to fetch labels
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(anyString(), anyMap()))
                            .thenReturn(viewer, noLabels, repoLabels, modifiedLabel);

                    // Mocked REST request
                    PagedIterable<GHPullRequestFileDetail> paths = mockPagedIterable(
                            mockGHPullRequestFileDetail("bylaws/README.md"));
                    when(mocks.pullRequest(id).listFiles()).thenReturn(paths);
                })
                .when().payloadFromClasspath("/github/eventPullRequestCreatedBylaws.json")
                .event(GHEvent.PULL_REQUEST)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId), times(4))
                            .executeSync(anyString(), anyMap());
                });

        verifyLabelCache(prNodeId, 1, List.of("notice"));
        Assertions.assertNotNull(QueryHelper.QueryCache.GLOB.getCachedValue("bylaws/*", Object.class),
                "bylaws/* GLOB cache should exist");
        Assertions.assertNull(QueryHelper.QueryCache.GLOB.getCachedValue("policy/*", Object.class),
                "policy/* GLOB cache should not exist");
    }

    @Test
    public void testLabelChanged() throws Exception {
        String repoId = "R_kgDOLDuJqg";

        // preload the cache: no request to fetch labels (and check our work)
        Set<DataLabel> existing = new HashSet<>();
        existing.add(bug);
        QueryHelper.QueryCache.LABELS.putCachedValue(repoId, existing);
        verifyLabelCache(repoId, 1, List.of("bug"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryAppConfig.NAME).fromClasspath("/cf-label.yml");
                })
                .when().payloadFromClasspath("/github/eventLabelCreated.json")
                .event(GHEvent.LABEL)
                .then().github(mocks -> {
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        verifyLabelCache(repoId, 2, List.of("bug", "notice"));
    }

    private void verifyNoLabelCache(String labelId) {
        Assertions.assertNull(QueryHelper.QueryCache.LABELS.getCachedValue(labelId, Set.class),
                "Label cache for " + labelId + " should not exist");
    }

    private void verifyLabelCache(String labeledId, int size, List<String> expectedLabels) {
        @SuppressWarnings("unchecked")
        Set<DataLabel> labels = QueryHelper.QueryCache.LABELS.getCachedValue(labeledId, Set.class);

        Assertions.assertNotNull(labels);
        Assertions.assertEquals(size, labels.size(), stringify(labels));

        if (size > 0) {
            expectedLabels.forEach(expectedLabel -> {
                System.out.println("expectedLabel: " + expectedLabel);
                System.out.println(labels);
                Assertions.assertTrue(labels.stream().anyMatch(label -> label.name.equals(expectedLabel)));
            });
        }
    }

    public String stringify(Collection<DataLabel> labels) {
        return labels.stream().map(label -> label.name).collect(Collectors.joining(", "));
    }

    public static GHPullRequestFileDetail mockGHPullRequestFileDetail(String filename) {
        GHPullRequestFileDetail mock = mock(GHPullRequestFileDetail.class);
        lenient().when(mock.getFilename()).thenReturn(filename);
        return mock;
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
            throw new RuntimeException(e);
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
}
