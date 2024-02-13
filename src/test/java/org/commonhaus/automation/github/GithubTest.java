package org.commonhaus.automation.github;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.commonhaus.automation.github.model.DataLabel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.kohsuke.github.GHPullRequestFileDetail;
import org.kohsuke.github.PagedIterable;
import org.kohsuke.github.PagedIterator;
import org.mockito.Mockito;

import io.quarkus.arc.Arc;
import io.smallrye.graphql.client.Response;

public class GithubTest {
    static final String repoFullName = "commonhaus/automation-test";
    static final String repositoryId = "R_kgDOLDuJqg";
    static final long repoId = 742099370;
    static final long installationId = 46053716;

    static final DataLabel bug = new DataLabel.Builder().name("bug").id("LA_kwDOLDuJqs8AAAABfqsdNQ").build();
    static final DataLabel notice = new DataLabel.Builder().name("notice").id("LA_kwDOLDuJqs8AAAABgn2hGA").build();

    @BeforeEach
    void beforeEach() {
        QueryHelper.QueryCache.LABELS.invalidateAll();
        verifyNoLabelCache(repositoryId);
        Arc.container().instance(QueryHelper.class).get().setBotSenderLogin("login");
    }

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

    void verifyNoLabelCache(String labelId) {
        Assertions.assertNull(QueryHelper.QueryCache.LABELS.getCachedValue(labelId, Set.class),
                "Label cache for " + labelId + " should not exist");
    }

    void verifyLabelCache(String labeledId, int size, List<String> expectedLabels) {
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
