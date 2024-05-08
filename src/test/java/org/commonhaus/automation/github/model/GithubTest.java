package org.commonhaus.automation.github.model;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.commonhaus.automation.github.model.QueryHelper.BotComment;
import org.commonhaus.automation.github.voting.VoteEvent;
import org.commonhaus.automation.github.voting.VotingConsumer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.kohsuke.github.GHPullRequestFileDetail;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.PagedIterable;
import org.kohsuke.github.PagedIterator;
import org.mockito.Mockito;

import io.quarkus.arc.Arc;
import io.smallrye.graphql.client.GraphQLError;
import io.smallrye.graphql.client.Response;

public class GithubTest {
    public static final String repoFullName = "commonhaus/automation-test";
    public static final String repositoryId = "R_kgDOLDuJqg";
    public static final long repoId = 742099370;
    public static final long installationId = 46053716;
    public static final long organizationId = 144493209;

    public static final DataLabel bug = new DataLabel.Builder().name("bug").id("LA_kwDOLDuJqs8AAAABfqsdNQ").build();
    public static final DataLabel notice = new DataLabel.Builder().name("notice").id("LA_kwDOLDuJqs8AAAABgn2hGA").build();
    public static final DataLabel VOTE_OPEN = new DataLabel.Builder().name(VotingConsumer.VOTE_OPEN)
            .id("LA_kwDOKRPTI88AAAABgkXEVQ").build();
    public static final DataLabel VOTE_PROCEED = new DataLabel.Builder().name(VotingConsumer.VOTE_PROCEED).build();
    public static final DataLabel VOTE_QUORUM = new DataLabel.Builder().name(VotingConsumer.VOTE_QUORUM).build();
    public static final DataLabel VOTE_REVISE = new DataLabel.Builder().name(VotingConsumer.VOTE_REVISE).build();
    public static final DataLabel VOTE_DONE = new DataLabel.Builder().name(VotingConsumer.VOTE_DONE)
            .id("LA_kwDOKRPTI88AAAABhGp_7g").build();

    public static final String botCommentId = "DC_kwDOLDuJqs4Agx94";
    public static final Integer botCommentDatabaseId = 8593272;

    @BeforeEach
    protected void beforeEach() {
        QueryCache.TEAM.invalidateAll();
        QueryCache.LABELS.invalidateAll();
        QueryCache.RECENT_BOT_CONTENT.invalidateAll();
        verifyNoLabelCache(repositoryId);
        setLogin("login");
    }

    public void setLogin(String login) {
        Arc.container().instance(QueryHelper.class).get().setBotSenderLogin(login);
    }

    public void setLabels(String id, DataLabel... labels) {
        QueryCache.LABELS.computeIfAbsent(id, (k) -> new HashSet<>()).addAll(List.of(labels));
    }

    public void setLabels(String id, Set<DataLabel> labels) {
        QueryCache.LABELS.computeIfAbsent(id, (k) -> new HashSet<>()).addAll(labels);
    }

    public Response mockResponse(Path filename) {
        try {
            JsonObject jsonObject = Json.createReader(Files.newInputStream(filename)).readObject();

            Response mockResponse = Mockito.mock(Response.class);
            when(mockResponse.getData()).thenReturn(jsonObject);

            return mockResponse;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Response mockErrorResponse(String type, Path filename) {
        try {
            JsonObject jsonObject = Json.createReader(Files.newInputStream(filename)).readObject();

            Map<String, Object> otherFields = Map.of("type", "NOT_FOUND");

            GraphQLError error = Mockito.mock(GraphQLError.class);
            when(error.getMessage()).thenReturn("error message");
            when(error.getOtherFields()).thenReturn(otherFields);

            Response mockResponse = Mockito.mock(Response.class);
            when(mockResponse.getData()).thenReturn(jsonObject);
            when(mockResponse.hasError()).thenReturn(true);
            when(mockResponse.getErrors()).thenReturn(List.of(error));

            return mockResponse;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected void setupBotComment(String nodeId) {
        DataCommonComment comment = new DataCommonComment(
                Json.createObjectBuilder()
                        .add("id", botCommentId)
                        .add("databaseId", botCommentDatabaseId)
                        .add("url", "https://github.com/commonhaus/automation-test/discussions/25#discussioncomment-8593272")
                        .add("body", "This is a test comment")
                        .add("author", Json.createObjectBuilder()
                                .add("login", "commonhaus-test-bot")
                                .build())
                        .build());
        BotComment botComment = new BotComment(VoteEvent.botCommentPattern, nodeId, comment);
        QueryCache.RECENT_BOT_CONTENT.putCachedValue(nodeId, botComment);
    }

    public BotComment verifyBotCommentCache(String nodeId, String commentId) {
        // We're always updating the cache, but that often happens in a separate thread.
        // let's make sure all of the updates are done before proceeding to the next test
        await().atMost(5, SECONDS)
                .until(() -> QueryCache.RECENT_BOT_CONTENT.getCachedValue(nodeId) != null);

        BotComment botComment = QueryCache.RECENT_BOT_CONTENT.getCachedValue(nodeId);
        Assertions.assertEquals(commentId, botComment.getCommentId(),
                "Cached Bot Comment ID for " + nodeId + " should equal " + commentId + ", was " + botComment.getCommentId());
        return botComment;
    }

    public void verifyNoLabelCache(String labelId) {
        Assertions.assertNull(QueryCache.LABELS.getCachedValue(labelId),
                "Label cache for " + labelId + " should not exist");
    }

    public void verifyLabelCache(String labeledId, int size, List<String> expectedLabels) {
        Set<DataLabel> labels = QueryCache.LABELS.getCachedValue(labeledId);

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
