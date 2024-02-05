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

import com.hrakaroo.glob.MatchingEngine;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.graphql.client.Response;

@QuarkusTest
@GitHubAppTest
public class NotifyLabelsTest {
    final String repoFullName = "commonhaus/automation-test";
    final long repoId = 742099370;
    final long installationId = 46053716;

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
        QueryHelper.cache.invalidateAll();
        QueryHelper.cache.cleanUp();
    }

    @Test
    void discussionCreated() throws Exception {
        // When a general, not-interesting, discussion is created,
        // - labels are still fetched (label rule)
        // - no other rules or actions are triggered

        // from src/test/resources/github/eventDiscussionCreated.json
        String discussionId = "D_kwDOLDuJqs4AXaZU";

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
        // - email is sent
        // - a workflow is dispatched

        // from src/test/resources/github/eventDiscussionCreatedAnnouncements.json
        String discussionId = "D_kwDOLDuJqs4AXaZM";

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
        String repositoryId = "R_kgDOLDuJqg";

        // preload the cache: no request to fetch repo labels (and check our work)
        Set<DataLabel> existing = new HashSet<>();
        existing.add(new DataLabel.Builder().name("notice").build());
        QueryHelper.putCache(repositoryId, QueryHelper.LABEL, existing);
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
        existing.add(new DataLabel.Builder().name("bug").build());
        QueryHelper.putCache(discussionId, QueryHelper.LABEL, existing);
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
    void discussionUnlabeled() throws Exception {
        // When a discussion is labeled, ...
        // If we don't have the labels cached, we fetch them first
        // from src/test/resources/github/eventDiscussionUnlabeled.json
        String discussionId = "D_kwDOLDuJqs4AXNhB";
        Response bugLabel = mockResponse(Path.of("src/test/resources/github/queryLabelBug.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryAppConfig.NAME).fromClasspath("/cf-label.yml");
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(anyString(), anyMap()))
                            .thenReturn(bugLabel);
                })
                .when().payloadFromClasspath("/github/eventDiscussionUnlabeled.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId))
                            .executeSync(anyString(), anyMap());
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        verifyLabelCache(discussionId, 0, List.of());
    }

    @Test
    void testRelevantPr() throws Exception {
        String prNodeId = "PR_kwDOLDuJqs5lPq17";
        long id = 1698606459;
        Response repoLabels = mockResponse(Path.of("src/test/resources/github/queryRepositoryLabelsNotice.json"));
        Response bugLabel = mockResponse(Path.of("src/test/resources/github/queryLabelBug.json"));
        Response modifiedLabel = mockResponse(Path.of("src/test/resources/github/addLabelsToLabelableResponse.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryAppConfig.NAME).fromClasspath("/cf-label.yml");
                    // 2 GraphQL queries to fetch labels
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(anyString(), anyMap()))
                            .thenReturn(bugLabel, repoLabels, modifiedLabel);

                    // Mocked REST request
                    PagedIterable<GHPullRequestFileDetail> paths = mockPagedIterable(
                            mockGHPullRequestFileDetail("bylaws/README.md"));
                    when(mocks.pullRequest(id).listFiles()).thenReturn(paths);
                })
                .when().payloadFromClasspath("/github/eventPullRequestCreatedBylaws.json")
                .event(GHEvent.PULL_REQUEST)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId), times(3))
                            .executeSync(anyString(), anyMap());
                });

        verifyLabelCache(prNodeId, 1, List.of("notice"));
        Assertions.assertNotNull(QueryHelper.getCache("GLOB", "bylaws/*", Object.class),
                "bylaws/* GLOB cache should exist");
        Assertions.assertNull(QueryHelper.getCache("GLOB", "policy/*", Object.class),
                "policy/* GLOB cache should not exist");
    }

    private void verifyLabelCache(String discussionId, int size, List<String> expectedLabels) {
        Collection<DataLabel> labels = QueryHelper.getCache(discussionId, "labels", Collection.class);

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

    // public void repositoryLabelMocks(GitHubMockSetupContext mocks) throws IOException {
    //     GHLabel mockLabel = mock(GHLabel.class);
    //     when(mockLabel.getName()).thenReturn("notice");
    //     when(mockLabel.getId()).thenReturn(1L);
    //     when(mockLabel.getNodeId()).thenReturn("MDU6TGFiZWwx");

    //     PagedIterable<GHLabel> iterableMock = mockPagedIterable(mockLabel);
    //     when(mocks.repository(repoFullName).listLabels())
    //             .thenReturn(iterableMock);
    // }

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
