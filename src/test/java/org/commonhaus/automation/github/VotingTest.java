package org.commonhaus.automation.github;

import static io.quarkiverse.githubapp.testing.GitHubAppTesting.given;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import jakarta.json.Json;

import org.commonhaus.automation.github.model.DataCommonComment;
import org.commonhaus.automation.github.model.DataLabel;
import org.commonhaus.automation.github.model.GithubTest;
import org.commonhaus.automation.github.model.QueryHelper;
import org.commonhaus.automation.github.model.QueryHelper.QueryCache;
import org.commonhaus.automation.github.voting.VotingConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEvent;
import org.kohsuke.github.GHTeam;
import org.kohsuke.github.GHUser;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkiverse.githubapp.testing.dsl.GitHubMockSetupContext;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.graphql.client.Response;

@QuarkusTest
@GitHubAppTest
public class VotingTest extends GithubTest {
    static final DataLabel VOTE_OPEN = new DataLabel.Builder().name(VotingConsumer.VOTE_OPEN).build();
    static final DataLabel VOTE_PROCEED = new DataLabel.Builder().name(VotingConsumer.VOTE_PROCEED).build();
    static final DataLabel VOTE_QUORUM = new DataLabel.Builder().name(VotingConsumer.VOTE_QUORUM).build();
    static final DataLabel VOTE_REVISE = new DataLabel.Builder().name(VotingConsumer.VOTE_REVISE).build();
    static final DataLabel VOTE_DONE = new DataLabel.Builder().name(VotingConsumer.VOTE_DONE).build();

    static final Set<DataLabel> REPO_LABELS = Set.of(
            VOTE_DONE, VOTE_OPEN, VOTE_PROCEED, VOTE_QUORUM, VOTE_REVISE);
    static final Set<DataLabel> ITEM_OPEN = Set.of(VOTE_OPEN);
    static final Set<DataLabel> ITEM_CLOSING = Set.of(VOTE_DONE, VOTE_OPEN);

    static final String discussionId = "D_kwDOLDuJqs4AXteZ";
    static final String botCommentId = "DC_kwDOLDuJqs4Agx94";
    static final Integer botCommentDatabaseId = 8593272;

    void setupTeamReactions(GitHubMockSetupContext mocks) throws IOException {
        GHUser user1 = mockGHUser("user1");
        GHUser user2 = mockGHUser("user2");
        GHUser user3 = mockGHUser("user3");
        mockGHUser("user4");

        var team = mocks.ghObject(GHTeam.class, 1);
        when(team.getMembers())
                .thenReturn(Set.of(user1, user2, user3));

        QueryHelper.QueryCache.TEAM.putCachedValue("commonhaus/test-quorum-default", team);
    }

    void setupBotComment() {
        QueryCache.RECENT_BOT_CONTENT.putCachedValue(discussionId, new DataCommonComment(
                Json.createObjectBuilder()
                        .add("id", botCommentId)
                        .add("databaseId", botCommentDatabaseId)
                        .add("url", "https://github.com/commonhaus/automation-test/discussions/25#discussioncomment-8593272")
                        .add("body", "This is a test comment")
                        .add("author", Json.createObjectBuilder()
                                .add("login", "commonhaus-test-bot")
                                .build())
                        .build()));
    }

    @BeforeEach
    @Override
    protected void beforeEach() {
        super.beforeEach();
        setLogin("commonhaus-test-bot");
    }

    @Test
    void testVoteOpenNoGroupNoComment() throws Exception {
        // Missing group: add confused reaction, add comment

        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(discussionId, ITEM_OPEN);

        Response addReaction = mockResponse(Path.of("src/test/resources/github/addConfusedReaction.json"));
        Response hasNoComments = mockResponse(Path.of("src/test/resources/github/responseNoComments.json"));
        Response addComment = mockResponse(Path.of("src/test/resources/github/addBotComment.json"));
        Response updateDescription = mockResponse(Path.of("src/test/resources/github/updateDiscussion.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryAppConfig.NAME).fromClasspath("/cf-voting.yml");
                    when(mocks.installationClient(installationId).isCredentialValid())
                            .thenReturn(true);

                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("addReaction("), anyMap()))
                            .thenReturn(addReaction);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("query($itemId: ID!, $after: String)"), anyMap()))
                            .thenReturn(hasNoComments);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("addDiscussionComment("), anyMap()))
                            .thenReturn(addComment);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("updateDiscussion("), anyMap()))
                            .thenReturn(updateDescription);
                })
                .when().payloadFromClasspath("/github/eventDiscussionEditedNoGroup.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    // add confused reaction, create comment, update body with comment reference
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("addReaction("), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("query($itemId: ID!, $after: String)"), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("addDiscussionComment("), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("updateDiscussion("), anyMap());

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
        verifyBotCommentCache(discussionId, botCommentId);
    }

    @Test
    void testVoteOpenNoGroupWithComment() throws Exception {
        // Missing Martha's info:
        // add confused reaction, update existing comment
        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(discussionId, ITEM_OPEN);

        Response addReaction = mockResponse(Path.of("src/test/resources/github/addConfusedReaction.json"));
        Response hasComments = mockResponse(Path.of("src/test/resources/github/responseHasComments.json"));
        Response updateComment = mockResponse(Path.of("src/test/resources/github/updateDiscussionComment.json"));
        Response updateDescription = mockResponse(Path.of("src/test/resources/github/updateDiscussion.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryAppConfig.NAME).fromClasspath("/cf-voting.yml");
                    when(mocks.installationClient(installationId).isCredentialValid())
                            .thenReturn(true);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("addReaction("), anyMap()))
                            .thenReturn(addReaction);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("query($itemId: ID!, $after: String)"), anyMap()))
                            .thenReturn(hasComments);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("updateDiscussionComment("), anyMap()))
                            .thenReturn(updateComment);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("updateDiscussion("), anyMap()))
                            .thenReturn(updateDescription);
                })
                .when().payloadFromClasspath("/github/eventDiscussionEditedNoGroup.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("addReaction("), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("($itemId: ID!, $after: String)"), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("updateDiscussionComment("), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("updateDiscussion("), anyMap());
                });
        verifyBotCommentCache(discussionId, botCommentId);
    }

    @Test
    void testVoteOpenNoMarthas() throws Exception {
        // Valid votes!
        // Leftover confused reaction to remove
        // Add comment with vote summary

        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(discussionId, ITEM_OPEN);

        Response addReaction = mockResponse(Path.of("src/test/resources/github/addConfusedReaction.json"));
        Response botComment = mockResponse(Path.of("src/test/resources/github/responseBotComment.json"));
        Response updateComment = mockResponse(Path.of("src/test/resources/github/updateDiscussionComment.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryAppConfig.NAME).fromClasspath("/cf-voting.yml");

                    when(mocks.installationClient(installationId).isCredentialValid())
                            .thenReturn(true);

                    setupTeamReactions(mocks);

                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("addReaction("), anyMap()))
                            .thenReturn(addReaction);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("query($commentId: ID!) {"), anyMap()))
                            .thenReturn(botComment);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("updateDiscussionComment("), anyMap()))
                            .thenReturn(updateComment);
                })
                .when().payloadFromClasspath("/github/eventDiscussionEditedNoMarthas.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("addReaction("), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("query($commentId: ID!) {"), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("updateDiscussionComment("), anyMap());

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
        verifyBotCommentCache(discussionId, botCommentId);
    }

    @Test
    void testVoteOpenValidVoteNoReactions() throws Exception {
        // Valid vote; no reactions -- team members not queried; comment updated

        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(discussionId, ITEM_OPEN);

        Response reactions = mockResponse(Path.of("src/test/resources/github/queryReactionsNone.json"));
        Response updateComment = mockResponse(Path.of("src/test/resources/github/updateDiscussionComment.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryAppConfig.NAME).fromClasspath("/cf-voting.yml");
                    when(mocks.installationClient(installationId).isCredentialValid())
                            .thenReturn(true);

                    setupTeamReactions(mocks);
                    setupBotComment();

                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("... on Reactable {"), anyMap()))
                            .thenReturn(reactions);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("updateDiscussionComment("), anyMap()))
                            .thenReturn(updateComment);

                })
                .when().payloadFromClasspath("/github/eventDiscussionEditedValidVote.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("... on Reactable {"), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("updateDiscussionComment("), anyMap());

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        verifyBotCommentCache(discussionId, botCommentId);
    }

    @Test
    void testVoteOpenValidVoteAllTeam() throws Exception {
        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(discussionId, ITEM_OPEN);

        Response reactions = mockResponse(Path.of("src/test/resources/github/queryReactionsAllTeam.json"));
        Response removeReaction = mockResponse(Path.of("src/test/resources/github/removeConfusedReaction.json"));
        Response updateComment = mockResponse(Path.of("src/test/resources/github/updateDiscussionComment.json"));
        Response addLabel = mockResponse(Path.of("src/test/resources/github/addVotingQuorumLabelResponse.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryAppConfig.NAME).fromClasspath("/cf-voting.yml");
                    when(mocks.installationClient(installationId).isCredentialValid())
                            .thenReturn(true);

                    setupTeamReactions(mocks);
                    setupBotComment();

                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("... on Reactable {"), anyMap()))
                            .thenReturn(reactions);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("RemoveReaction("), anyMap()))
                            .thenReturn(removeReaction);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("updateDiscussionComment("), anyMap()))
                            .thenReturn(updateComment);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("addLabelsToLabelable("), anyMap()))
                            .thenReturn(addLabel);
                })
                .when().payloadFromClasspath("/github/eventDiscussionEditedValidVote.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("... on Reactable {"), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("RemoveReaction("), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("updateDiscussionComment("), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("addLabelsToLabelable("), anyMap());
                    verify(mocks.ghObject(GHTeam.class, 1), timeout(500))
                            .getMembers();

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
        verifyBotCommentCache(discussionId, botCommentId);
    }

    @Test
    void testVoteOpenValidVoteSomeTeam() throws Exception {
        // Valid vote; only some team have voted + extra; update comment

        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(discussionId, ITEM_OPEN);

        Response reactions = mockResponse(Path.of("src/test/resources/github/queryReactionsSomeTeam.json"));
        Response updateComment = mockResponse(Path.of("src/test/resources/github/updateDiscussionComment.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryAppConfig.NAME).fromClasspath("/cf-voting.yml");
                    when(mocks.installationClient(installationId).isCredentialValid())
                            .thenReturn(true);

                    setupTeamReactions(mocks);
                    setupBotComment();

                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("... on Reactable {"), anyMap()))
                            .thenReturn(reactions);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("updateDiscussionComment("), anyMap()))
                            .thenReturn(updateComment);
                })
                .when().payloadFromClasspath("/github/eventDiscussionEditedValidVote.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("... on Reactable {"), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("updateDiscussionComment("), anyMap());
                    verify(mocks.ghObject(GHTeam.class, 1), timeout(500))
                            .getMembers();

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
        verifyBotCommentCache(discussionId, botCommentId);
    }
}
