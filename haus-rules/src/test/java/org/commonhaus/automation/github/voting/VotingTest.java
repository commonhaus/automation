package org.commonhaus.automation.github.voting;

import static io.quarkiverse.githubapp.testing.GitHubAppTesting.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import jakarta.inject.Inject;
import jakarta.json.Json;

import org.commonhaus.automation.RepositoryConfigFile;
import org.commonhaus.automation.github.EventQueryContext;
import org.commonhaus.automation.github.context.BotComment;
import org.commonhaus.automation.github.context.DataActor;
import org.commonhaus.automation.github.context.DataCommonComment;
import org.commonhaus.automation.github.context.DataLabel;
import org.commonhaus.automation.github.context.DataReaction;
import org.commonhaus.automation.github.context.EventData;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.github.context.QueryContext;
import org.commonhaus.automation.github.test.ContextHelper;
import org.commonhaus.automation.github.voting.VoteConfig.AlternateConfig;
import org.commonhaus.automation.github.voting.VoteConfig.AlternateDefinition;
import org.commonhaus.automation.github.voting.VoteConfig.TeamMapping;
import org.commonhaus.automation.github.voting.VoteInformation.Alternates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEvent;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.ReactionContent;
import org.mockito.Mockito;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.graphql.client.Response;

@QuarkusTest
@GitHubAppTest
public class VotingTest extends ContextHelper {
    static final Set<DataLabel> REPO_LABELS = Set.of(
            VOTE_DONE, VOTE_OPEN, VOTE_QUORUM, VOTE_PROCEED, VOTE_REVISE);
    static final Set<DataLabel> ITEM_VOTE_OPEN = Set.of(VOTE_OPEN);
    static final String discussionId = "D_kwDOLDuJqs4AXteZ";

    ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    MockMailbox mailbox;

    @BeforeEach
    @Override
    protected void beforeEach() {
        super.beforeEach();
        setLogin("commonhaus-test-bot");
        mailbox.clear();
    }

    @AfterEach
    protected void noErrorMail() {
        VoteInformation.ALT_ACTORS.invalidateAll();
        await().failFast(() -> mailbox.getTotalMessagesSent() != 0)
                .atMost(3, TimeUnit.SECONDS);
    }

    @Test
    void testVoteOpenNoGroupNoComment() throws Exception {
        // Missing group: add confused reaction, add comment

        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(discussionId, ITEM_VOTE_OPEN);

        Response addReaction = mockResponse(Path.of("src/test/resources/github/mutableAddReaction.Confused.json"));
        Response hasNoComments = mockResponse(Path.of("src/test/resources/github/queryComments.None.json"));
        Response addComment = mockResponse(Path.of("src/test/resources/github/mutableAddDiscussionComment.json"));
        Response updateDescription = mockResponse(Path.of("src/test/resources/github/mutableUpdateDiscussion.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-voting.yml");
                    setupMockTeam(mocks);

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
        setLabels(discussionId, ITEM_VOTE_OPEN);

        Response addReaction = mockResponse(Path.of("src/test/resources/github/mutableAddReaction.Confused.json"));
        Response hasComments = mockResponse(Path.of("src/test/resources/github/queryComments.json"));
        Response updateComment = mockResponse(
                Path.of("src/test/resources/github/mutableUpdateDiscussionComment.json"));
        Response updateDescription = mockResponse(Path.of("src/test/resources/github/mutableUpdateDiscussion.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-voting.yml");
                    setupMockTeam(mocks);

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
    void testVoteOpenMarthas() throws Exception {
        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(discussionId, ITEM_VOTE_OPEN);

        Response addReaction = mockResponse(Path.of("src/test/resources/github/mutableAddReaction.Confused.json"));
        Response findBotComment = mockResponse(Path.of("src/test/resources/github/queryComments.Bot.json"));
        Response updateComment = mockResponse(
                Path.of("src/test/resources/github/mutableUpdateDiscussionComment.json"));
        Response updateDescription = mockResponse(Path.of("src/test/resources/github/mutableUpdateDiscussion.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-voting.yml");

                    when(mocks.installationClient(installationId).isCredentialValid())
                            .thenReturn(true);

                    setupMockTeam(mocks);

                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("addReaction("), anyMap()))
                            .thenReturn(addReaction);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("query($commentId: ID!) {"), anyMap()))
                            .thenReturn(findBotComment);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("updateDiscussionComment("), anyMap()))
                            .thenReturn(updateComment);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("updateDiscussion("), anyMap()))
                            .thenReturn(updateDescription);
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
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("updateDiscussion("), anyMap());

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });
        verifyBotCommentCache(discussionId, botCommentId);
    }

    @Test
    void testVoteOpenValidVoteNoReactions() throws Exception {
        // Valid vote; no reactions -- team members not queried; comment updated

        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(discussionId, ITEM_VOTE_OPEN);

        Response reactions = mockResponse(Path.of("src/test/resources/github/queryReactionsNone.json"));
        Response updateComment = mockResponse(
                Path.of("src/test/resources/github/mutableUpdateDiscussionComment.json"));
        Response updateDescription = mockResponse(Path.of("src/test/resources/github/mutableUpdateDiscussion.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-voting.yml");
                    when(mocks.installationClient(installationId).isCredentialValid())
                            .thenReturn(true);

                    setupMockTeam(mocks);
                    setupBotComment(discussionId);

                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("reactions(first: 100"), anyMap()))
                            .thenReturn(reactions);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("updateDiscussionComment("), anyMap()))
                            .thenReturn(updateComment);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("updateDiscussion("), anyMap()))
                            .thenReturn(updateDescription);
                })
                .when().payloadFromClasspath("/github/eventDiscussionEditedValidVote.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("reactions(first: 100"), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("updateDiscussionComment("), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("updateDiscussion("), anyMap());

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });

        BotComment comment = verifyBotCommentCache(discussionId, botCommentId);
        assertThat(comment.getBody()).contains("fourfifths");
    }

    @Test
    void testVoteOpenValidVoteRemoveReaction() throws Exception {
        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(discussionId, ITEM_VOTE_OPEN);

        Response reactions = mockResponse(Path.of("src/test/resources/github/queryReactionsAllTeam.json"));
        Response removeReaction = mockResponse(
                Path.of("src/test/resources/github/mutableRemoveReaction.Confused.json"));
        Response updateComment = mockResponse(
                Path.of("src/test/resources/github/mutableUpdateDiscussionComment.json"));
        Response addLabel = mockResponse(
                Path.of("src/test/resources/github/mutableAddLabelsToLabelable.VotingQuorum.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-voting.yml");
                    when(mocks.installationClient(installationId).isCredentialValid())
                            .thenReturn(true);

                    setupMockTeam(mocks);
                    setupBotComment(discussionId);

                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("reactions(first: 100"), anyMap()))
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
                .when().payloadFromClasspath("/github/eventDiscussionEditedValidVoteCorrectComment.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("reactions(first: 100"), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("RemoveReaction("), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("updateDiscussionComment("), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("addLabelsToLabelable("), anyMap());

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });
        verifyBotCommentCache(discussionId, botCommentId);
    }

    @Test
    void testVoteOpenValidVote() throws Exception {
        // Valid vote; only some team have voted + extra; update comment

        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(discussionId, ITEM_VOTE_OPEN);

        Response reactions = mockResponse(Path.of("src/test/resources/github/queryReactionsSomeTeam.json"));
        Response updateComment = mockResponse(
                Path.of("src/test/resources/github/mutableUpdateDiscussionComment.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-voting.yml");
                    when(mocks.installationClient(installationId).isCredentialValid())
                            .thenReturn(true);
                    when(mocks.repository(repoFullName).getFullName())
                            .thenReturn(repoFullName);

                    setupMockTeam(mocks);
                    setupBotComment(discussionId);

                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("reactions(first: 100"), anyMap()))
                            .thenReturn(reactions);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("updateDiscussionComment("), anyMap()))
                            .thenReturn(updateComment);
                })
                .when().payloadFromClasspath("/github/eventDiscussionEditedValidVoteCorrectComment.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("reactions(first: 100"), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("updateDiscussionComment("), anyMap());
                    verify(mocks.repository(repoFullName), atLeastOnce()).getFullName();

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });
        verifyBotCommentCache(discussionId, botCommentId);
    }

    @Test
    void testVoteOpenValidCommentsVote() throws Exception {
        // Valid vote; only some team have voted + extra; update comment

        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(discussionId, ITEM_VOTE_OPEN);

        Response teamComments = mockResponse(Path.of("src/test/resources/github/queryComments.VoteComments.json"));
        Response updateComment = mockResponse(
                Path.of("src/test/resources/github/mutableUpdateDiscussionComment.json"));
        Response addLabel = mockResponse(
                Path.of("src/test/resources/github/mutableAddLabelsToLabelable.VotingQuorum.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-voting.yml");
                    when(mocks.installationClient(installationId).isCredentialValid())
                            .thenReturn(true);

                    GHUser user1 = mockGHUser(mocks, "nmcl");
                    GHUser user2 = mockGHUser(mocks, "evanchooly");
                    GHUser user3 = mockGHUser(mocks, "kenfinnigan");
                    mockGHUser(mocks, "ebullient");

                    setupMockTeam(mocks, user1, user2, user3);
                    setupBotComment(discussionId);

                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("query($itemId: ID!, $after: String) {"), anyMap()))
                            .thenReturn(teamComments);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("updateDiscussionComment("), anyMap()))
                            .thenReturn(updateComment);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("addLabelsToLabelable("), anyMap()))
                            .thenReturn(addLabel);
                })
                .when().payloadFromClasspath("/github/eventDiscussionEditedComments.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("query($itemId: ID!, $after: String) {"), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("updateDiscussionComment("), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("addLabelsToLabelable("), anyMap());

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });
        verifyBotCommentCache(discussionId, botCommentId);
    }

    @Test
    void testVoteOpenValidManualVote() throws Exception {
        // Valid vote; only some team have voted + extra; update comment

        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(discussionId, ITEM_VOTE_OPEN);

        Response reactions = mockResponse(Path.of("src/test/resources/github/queryReactionsSomeTeam.json"));
        Response updateComment = mockResponse(
                Path.of("src/test/resources/github/mutableUpdateDiscussionComment.json"));
        Response addLabel = mockResponse(
                Path.of("src/test/resources/github/mutableAddLabelsToLabelable.VotingQuorum.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-voting.yml");
                    when(mocks.installationClient(installationId).isCredentialValid())
                            .thenReturn(true);

                    GHUser user1 = mockGHUser(mocks, "nmcl");
                    GHUser user2 = mockGHUser(mocks, "evanchooly");
                    GHUser user3 = mockGHUser(mocks, "kenfinnigan");
                    mockGHUser(mocks, "ebullient");

                    setupMockTeam(mocks, user1, user2, user3);
                    setupBotComment(discussionId);

                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("reactions(first: 100"), anyMap()))
                            .thenReturn(reactions);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("updateDiscussionComment("), anyMap()))
                            .thenReturn(updateComment);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("addLabelsToLabelable("), anyMap()))
                            .thenReturn(addLabel);
                })
                .when().payloadFromClasspath("/github/eventDiscussionEditedManual.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("reactions(first: 100"), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("updateDiscussionComment("), anyMap());

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });
        BotComment botComment = verifyBotCommentCache(discussionId, botCommentId);
        assertThat(botComment.getBody()).contains(
                "Additional input (🙏 🥰 🙌):",
                "outsider");
    }

    @Test
    public void testManualResultsCommentAdded() throws Exception {
        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(discussionId, ITEM_VOTE_OPEN);

        Response reactions = mockResponse(Path.of("src/test/resources/github/queryReactionsNone.json"));
        Response updateComment = mockResponse(
                Path.of("src/test/resources/github/mutableUpdateDiscussionComment.json"));
        Response updateDescription = mockResponse(Path.of("src/test/resources/github/mutableUpdateDiscussion.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-voting.yml");

                    setupMockTeam(mocks);
                    setupBotComment(discussionId);

                    when(mocks.installationClient(installationId).isCredentialValid())
                            .thenReturn(true);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("reactions(first: 100"), anyMap()))
                            .thenReturn(reactions);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("updateDiscussionComment("), anyMap()))
                            .thenReturn(updateComment);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("updateDiscussion("), anyMap()))
                            .thenReturn(updateDescription);
                })
                .when().payloadFromClasspath("/github/eventDiscussionCommentCreated.VoteResult.NonManager.json")
                .event(GHEvent.DISCUSSION_COMMENT)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("reactions(first: 100"), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("updateDiscussionComment("), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("updateDiscussion("), anyMap());
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });

        BotComment botComment = verifyBotCommentCache(discussionId, botCommentId);
        assertThat(botComment.getBody()).doesNotContain("This vote has been [closed]");
    }

    @Test
    public void testManualResultsCommentAddedByManager() throws Exception {
        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(discussionId, ITEM_VOTE_OPEN);

        Response reactions = mockResponse(Path.of("src/test/resources/github/queryReactionsNone.json"));
        Response updateComment = mockResponse(
                Path.of("src/test/resources/github/mutableUpdateDiscussionComment.json"));
        Response updateDescription = mockResponse(Path.of("src/test/resources/github/mutableUpdateDiscussion.json"));
        Response removeLabel = mockResponse(
                Path.of("src/test/resources/github/mutableRemoveLabelsFromLabelable.json"));
        Response addLabel = mockResponse(
                Path.of("src/test/resources/github/mutableAddLabelsToLabelable.VotingDone.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-voting.yml");

                    GHUser manager = mockGHUser(mocks, "ebullient");
                    setupMockTeam(mocks, manager);
                    setupBotComment(discussionId);

                    when(mocks.installationClient(installationId).isCredentialValid())
                            .thenReturn(true);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("reactions(first: 100"), anyMap()))
                            .thenReturn(reactions);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("updateDiscussionComment("), anyMap()))
                            .thenReturn(updateComment);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("updateDiscussion("), anyMap()))
                            .thenReturn(updateDescription);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("removeLabelsFromLabelable("), anyMap()))
                            .thenReturn(removeLabel);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("addLabelsToLabelable("), anyMap()))
                            .thenReturn(addLabel);
                })
                .when().payloadFromClasspath("/github/eventDiscussionCommentCreated.VoteResult.Manager.json")
                .event(GHEvent.DISCUSSION_COMMENT)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("reactions(first: 100"), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("updateDiscussionComment("), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("updateDiscussion("), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("removeLabelsFromLabelable("), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("addLabelsToLabelable("), anyMap());
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });

        BotComment botComment = verifyBotCommentCache(discussionId, botCommentId);
        assertThat(botComment.getBody()).contains("This vote has been [closed]");
    }

    @Test
    void testVoteOpenPullRequest() throws Exception {
        String pullRequestId = "PR_kwDOLDuJqs5mlMVl";

        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(pullRequestId, ITEM_VOTE_OPEN);

        Response reactions = mockResponse(Path.of("src/test/resources/github/queryReactionsSomeTeam.json"));
        Response reviews = mockResponse(Path.of("src/test/resources/github/queryPullRequestReviews.json"));
        Response updateComment = mockResponse(Path.of("src/test/resources/github/mutableUpdateIssueComment.json"));
        Response updatePullRequest = mockResponse(Path.of("src/test/resources/github/mutableUpdatePullRequest.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-voting.yml");
                    when(mocks.installationClient(installationId).isCredentialValid())
                            .thenReturn(true);

                    setupMockTeam(mocks);
                    setupBotComment(pullRequestId);

                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("reactions(first: 100"), anyMap()))
                            .thenReturn(reactions);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("latestReviews(first: 100"), anyMap()))
                            .thenReturn(reviews);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("updateIssueComment("), anyMap()))
                            .thenReturn(updateComment);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("updatePullRequest("), anyMap()))
                            .thenReturn(updatePullRequest);
                })
                .when().payloadFromClasspath("/github/eventPullRequestReviewRequested.json")
                .event(GHEvent.PULL_REQUEST)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("reactions(first: 100"), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("latestReviews(first: 100"), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("updateIssueComment("), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("updatePullRequest("), anyMap());

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });

        BotComment botComment = verifyBotCommentCache(pullRequestId, botCommentId);
        assertThat(botComment.getBody()).contains(
                "Additional input (🙏 🥰 🙌):",
                "outsider",
                "Common title");
    }

    @Test
    public void testPullRequestOpenItemClosed() throws Exception {
        String pullRequestId = "PR_kwDOLDuJqs5mDkwX";

        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(pullRequestId, ITEM_VOTE_OPEN);

        Response reactions = mockResponse(Path.of("src/test/resources/github/queryReactionsSomeTeam.json"));
        Response reviews = mockResponse(Path.of("src/test/resources/github/queryPullRequestReviews.json"));
        Response teamComments = mockResponse(Path.of("src/test/resources/github/queryComments.ManualVoteResult.json"));
        Response updateComment = mockResponse(Path.of("src/test/resources/github/mutableUpdateIssueComment.json"));
        Response updatePullRequest = mockResponse(Path.of("src/test/resources/github/mutableUpdatePullRequest.json"));
        Response removeLabel = mockResponse(
                Path.of("src/test/resources/github/mutableRemoveLabelsFromLabelable.json"));
        Response addLabel = mockResponse(
                Path.of("src/test/resources/github/mutableAddLabelsToLabelable.VotingDone.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-voting.yml");
                    when(mocks.installationClient(installationId).isCredentialValid())
                            .thenReturn(true);

                    GHUser user1 = mockGHUser(mocks, "commonhaus-bot");
                    GHUser user2 = mockGHUser(mocks, "ebullient");

                    setupMockTeam(mocks, user1, user2);
                    setupBotComment(pullRequestId);

                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("comments(first: 50"), anyMap()))
                            .thenReturn(teamComments);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("reactions(first: 100"), anyMap()))
                            .thenReturn(reactions);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("latestReviews(first: 100"), anyMap()))
                            .thenReturn(reviews);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("updateIssueComment("), anyMap()))
                            .thenReturn(updateComment);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("updatePullRequest("), anyMap()))
                            .thenReturn(updatePullRequest);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("removeLabelsFromLabelable("), anyMap()))
                            .thenReturn(removeLabel);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("addLabelsToLabelable("), anyMap()))
                            .thenReturn(addLabel);
                })
                .when().payloadFromClasspath("/github/eventPullRequestClosed.json")
                .event(GHEvent.PULL_REQUEST)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("comments(first: 50"), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("reactions(first: 100"), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("latestReviews(first: 100"), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("updateIssueComment("), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("updatePullRequest("), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("removeLabelsFromLabelable("), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("addLabelsToLabelable("), anyMap());
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });

        BotComment botComment = verifyBotCommentCache(pullRequestId, botCommentId);
        assertThat(botComment.getBody()).contains(
                "This vote has been [closed]",
                "Additional input (🙏 🥰 🙌):",
                "outsider",
                "Common title");
    }

    @Test
    public void testPullRequestOpenItemClosedVoteResultComment() throws Exception {
        String pullRequestId = "PR_kwDOLDuJqs5mlMVl";

        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(pullRequestId, ITEM_VOTE_OPEN);

        Response reactions = mockResponse(Path.of("src/test/resources/github/queryReactionsSomeTeam.json"));
        Response reviews = mockResponse(Path.of("src/test/resources/github/queryPullRequestReviews.json"));
        Response teamComments = mockResponse(Path.of("src/test/resources/github/queryComments.ManualVoteResult.json"));
        Response updateComment = mockResponse(Path.of("src/test/resources/github/mutableUpdateIssueComment.json"));
        Response updatePullRequest = mockResponse(Path.of("src/test/resources/github/mutableUpdatePullRequest.json"));
        Response removeLabel = mockResponse(
                Path.of("src/test/resources/github/mutableRemoveLabelsFromLabelable.json"));
        Response addLabel = mockResponse(
                Path.of("src/test/resources/github/mutableAddLabelsToLabelable.VotingDone.json"));

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-voting.yml");
                    when(mocks.installationClient(installationId).isCredentialValid())
                            .thenReturn(true);

                    GHUser user1 = mockGHUser(mocks, "commonhaus-bot");
                    GHUser user2 = mockGHUser(mocks, "ebullient");

                    setupMockTeam(mocks, user1, user2);
                    setupBotComment(pullRequestId);

                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("comments(first: 50"), anyMap()))
                            .thenReturn(teamComments);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("reactions(first: 100"), anyMap()))
                            .thenReturn(reactions);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("latestReviews(first: 100"), anyMap()))
                            .thenReturn(reviews);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("updateIssueComment("), anyMap()))
                            .thenReturn(updateComment);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("updatePullRequest("), anyMap()))
                            .thenReturn(updatePullRequest);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("removeLabelsFromLabelable("), anyMap()))
                            .thenReturn(removeLabel);
                    when(mocks.installationGraphQLClient(installationId)
                            .executeSync(contains("addLabelsToLabelable("), anyMap()))
                            .thenReturn(addLabel);
                })
                .when().payloadFromClasspath("/github/eventIssueCommentCreated.json")
                .event(GHEvent.ISSUE_COMMENT)
                .then().github(mocks -> {
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("reactions(first: 100"), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("latestReviews(first: 100"), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("updateIssueComment("), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("updatePullRequest("), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("removeLabelsFromLabelable("), anyMap());
                    verify(mocks.installationGraphQLClient(installationId), timeout(500))
                            .executeSync(contains("addLabelsToLabelable("), anyMap());
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });

        BotComment botComment = verifyBotCommentCache(pullRequestId, botCommentId);
        assertThat(botComment.getBody()).contains(
                "This vote has been [closed]",
                "Additional input (🙏 🥰 🙌):",
                "outsider");
    }

    @Test
    void testVoteTally() throws Exception {
        String body = """
                voting group: @commonhaus/test-quorum-default
                <!--vote::marthas approve="+1" ok="eyes" revise="-1" -->
                """;

        GHOrganization org = Mockito.mock(GHOrganization.class);
        when(org.getLogin()).thenReturn("commonhaus");

        EventQueryContext qc = Mockito.mock(EventQueryContext.class);
        when(qc.getOrganization()).thenReturn(org);
        when(qc.getTeamList(anyString())).thenCallRealMethod();
        when(qc.teamMembers(anyString())).thenCallRealMethod();
        when(qc.getRepositoryId()).thenReturn(repositoryId);

        List<DataReaction> teamReactions = new ArrayList<>(51);
        Set<GHUser> teamUsers = new HashSet<>(51);
        List<DataReaction> unignore = new ArrayList<>(3);
        List<DataReaction> duplicates = new ArrayList<>(3);
        List<DataReaction> primary = new ArrayList<>(1);
        Date date = new Date();
        for (int i = 1; i < 51; i++) {
            GHUser ghUser = mockGHUser(null, "user" + i);
            DataActor user = new DataActor(ghUser);
            teamUsers.add(ghUser);

            if (i == 28) {
                // don't add the primary user to the list of team reactions. Secondary user will be counted instead
                primary.add(new DataReaction(user, "thumbs_up", date));
            } else {
                teamReactions.add(new DataReaction(user,
                        i % 13 == 0 ? "rocket" : i % 3 == 0 ? "thumbs_down" : i % 2 == 0 ? "thumbs_up" : "eyes", date));
            }

            if (i % 13 == 0) {
                unignore.add(new DataReaction(user, "eyes", date));
            }
            if (i % 19 == 0) {
                duplicates.add(new DataReaction(user, "thumbs_down", date));
            }
        }
        teamUsers.add(mockGHUser(null, "excluded")); // should be excluded from totals

        List<DataReaction> extraReactions = new ArrayList<>(10);
        for (int i = 1; i < 11; i++) {
            DataActor user = new DataActor(mockGHUser(null, "extra" + i));
            extraReactions.add(new DataReaction(user,
                    i % 4 == 0 ? "rocket" : i % 3 == 0 ? "thumbs_down" : i % 2 == 0 ? "thumbs_up" : "eyes", date));
        }

        GHUser alternate = mockGHUser(null, "alt_1");
        DataActor alternateUser = new DataActor(alternate);
        extraReactions.add(new DataReaction(alternateUser, "thumbs_down", date)); // opposite of primary

        setupMockTeam("commonhaus/test-quorum-default", teamUsers);
        setupMockTeam("commonhaus/test-quorum-seconds", Set.of(alternate));

        VoteConfig votingConfig = new VoteConfig();
        votingConfig.voteThreshold = new java.util.HashMap<>();
        votingConfig.voteThreshold.put("commonhaus/test-quorum-default", VoteConfig.Threshold.all);
        votingConfig.excludeLogin = List.of("excluded");

        votingConfig.alternates = List.of(new AlternateConfig(
                "CONTACTS.yaml", "commonhaus/foundation",
                List.of(new AlternateDefinition("project",
                        new TeamMapping("egc", "commonhaus/test-quorum-default"),
                        new TeamMapping("egc-second", "commonhaus/test-quorum-seconds")))));

        setupMockAlternates(votingConfig.alternates.hashCode(),
                "commonhaus/test-quorum-default",
                Map.of(primary.get(0).user.login, alternateUser));

        VoteEvent event = createVoteEvent(qc, votingConfig,
                "commonhaus/test-quorum-default",
                VoteConfig.Threshold.all, body);

        // Martha's

        VoteInformation voteInfo = new VoteInformation(event);
        assertThat(voteInfo.isValid()).isTrue();
        assertThat(voteInfo.group).isEqualTo("commonhaus/test-quorum-default");
        assertThat(voteInfo.alternates).isNotNull();
        assertThat(voteInfo.votingThreshold).isEqualTo(VoteConfig.Threshold.all);
        assertThat(voteInfo.approve).containsExactlyInAnyOrder(ReactionContent.PLUS_ONE);
        assertThat(voteInfo.ok).containsExactlyInAnyOrder(ReactionContent.EYES);
        assertThat(voteInfo.revise).containsExactlyInAnyOrder(ReactionContent.MINUS_ONE);
        assertThat(voteInfo.voteType).isEqualTo(VoteInformation.Type.marthas);

        // All have voted: but some are ignored!
        // All have voted, but user28 vote is replaced by an alternate

        VoteTally voteTally = assertVoteTally(50, 47, false, voteInfo, extraReactions, teamReactions);
        assertThat(voteTally.categories.get("approve")).isNotNull();
        assertThat(voteTally.categories.get("ok")).isNotNull();
        assertThat(voteTally.categories.get("revise")).isNotNull();
        assertThat(voteTally.categories.get("ignored")).isNotNull();
        assertThat(voteTally.missingGroupActors).size().isEqualTo(4); // 3 ignored values, missing primary vote

        // now add the missing votes with valid (not ignored) and duplicate values
        extraReactions.addAll(unignore);
        extraReactions.addAll(duplicates);

        // secondary should be counted to meet quorum
        voteTally = assertVoteTally(50, true, voteInfo, extraReactions, teamReactions);

        // Majority have voted

        votingConfig.voteThreshold.put("commonhaus/test-quorum-default", VoteConfig.Threshold.majority);
        voteInfo = new VoteInformation(event);
        assertVoteTally(26, 27, true, voteInfo, extraReactions, teamReactions); // alt_1 still included
        assertVoteTally(23, 24, false, voteInfo, extraReactions, teamReactions); // alt_1 still included

        // Supermajority (2/3) have voted

        votingConfig.voteThreshold.put("commonhaus/test-quorum-default", VoteConfig.Threshold.twothirds);
        voteInfo = new VoteInformation(event);
        assertVoteTally(34, true, voteInfo, extraReactions, teamReactions);
        assertVoteTally(33, false, voteInfo, extraReactions, teamReactions);

        // Manual (count reactions implied)

        body = """
                voting group: @commonhaus/test-quorum-default
                <!--vote::manual -->
                """;

        event = createVoteEvent(qc, votingConfig, "commonhaus/test-quorum-default", VoteConfig.Threshold.all,
                body);

        voteInfo = new VoteInformation(event);
        assertThat(voteInfo.isValid()).isTrue();
        assertThat(voteInfo.approve).isEmpty();
        assertThat(voteInfo.ok).isEmpty();
        assertThat(voteInfo.revise).isEmpty();
        assertThat(voteInfo.voteType).isEqualTo(VoteInformation.Type.manualReactions);

        voteTally = assertVoteTally(50, true, voteInfo, extraReactions, teamReactions);
        assertThat(voteTally.categories).hasSize(4);
        assertThat(voteTally.categories.get("👍")).isNotNull();
        assertThat(voteTally.categories.get("👀")).isNotNull();
        assertThat(voteTally.categories.get("👎")).isNotNull();
        assertThat(voteTally.categories.get("🚀")).isNotNull();
        assertVoteTally(49, false, voteInfo, extraReactions, teamReactions);

        // Manual: count comments instead of reactions

        body = """
                voting group: @commonhaus/test-quorum-default
                <!--vote::manual comments -->
                """;

        event = createVoteEvent(qc, votingConfig, "commonhaus/test-quorum-default", VoteConfig.Threshold.all,
                body);

        voteInfo = new VoteInformation(event);
        assertThat(voteInfo.isValid()).isTrue();
        assertThat(voteInfo.approve).isEmpty();
        assertThat(voteInfo.ok).isEmpty();
        assertThat(voteInfo.revise).isEmpty();
        assertThat(voteInfo.voteType).isEqualTo(VoteInformation.Type.manualComments);

        voteTally = assertVoteTally(50, true, voteInfo, extraReactions, teamReactions);
        assertThat(voteTally.categories).hasSize(1);
    }

    VoteEvent createVoteEvent(QueryContext qc, VoteConfig votingConfig, String group,
            VoteConfig.Threshold threshold, String body) {
        EventData eventData = Mockito.mock(EventData.class);
        when(eventData.getItemBody()).thenReturn(body);
        when(eventData.getEventType()).thenReturn(EventType.discussion);

        votingConfig.voteThreshold.put("commonhaus/test-quorum-default", VoteConfig.Threshold.all);

        return new VoteEvent(qc, votingConfig, eventData);
    }

    VoteTally assertVoteTally(int numUsers, boolean expectHasQuorum,
            VoteInformation voteInfo,
            List<DataReaction> extraReactions,
            List<DataReaction> teamReactions) throws JsonProcessingException {
        return assertVoteTally(numUsers, numUsers, expectHasQuorum, voteInfo, extraReactions, teamReactions);
    }

    VoteTally assertVoteTally(int numUsers, int numVotes, boolean expectHasQuorum,
            VoteInformation voteInfo,
            List<DataReaction> extraReactions,
            List<DataReaction> teamReactions) throws JsonProcessingException {

        List<DataReaction> reactions = Stream
                .concat(teamReactions.stream(), extraReactions.stream())
                .filter(x -> Integer.parseInt(x.user.id.replaceAll("(user|extra|alt_)", "")) <= numUsers)
                .toList();
        List<DataCommonComment> comments = List.of();

        if (voteInfo.voteType == VoteInformation.Type.manualComments) {
            comments = reactions.stream()
                    .map(r -> new DataCommonComment(Json.createObjectBuilder()
                            .add("author", Json.createObjectBuilder()
                                    .add("login", r.user.login)
                                    .add("url", r.user.url)
                                    .add("id", r.user.id)
                                    .build())
                            .build()))
                    .toList();
        }

        VoteTally voteTally = new VoteTally(voteInfo, reactions, comments, List.of());

        String json = objectMapper.writeValueAsString(voteTally);
        assertThat(json)
                .contains(List.of("hasQuorum", "votingThreshold",
                        "group", "groupSize", "groupVotes", "countedVotes", "droppedVotes",
                        "categories", "duplicates", "missingGroupActors"));

        String markdown = voteTally.toMarkdown(false);
        assertThat(markdown)
                .as("should contain number of team member votes")
                .contains(numVotes + " of " + voteInfo.teamList.size() + " members");

        if (expectHasQuorum) {
            assertThat(voteTally.hasQuorum)
                    .as("vote should meet quorum")
                    .isTrue();
            assertThat(markdown)
                    .as("markdown should contain ✅")
                    .contains("✅ ");
        } else {
            assertThat(voteTally.hasQuorum)
                    .as("vote should not meet quorum")
                    .isFalse();
            assertThat(markdown)
                    .as("markdown should not contain ✅ (no quorum)")
                    .doesNotContain("✅ ");
        }

        if (!voteTally.duplicates.isEmpty()) {
            assertThat(json)
                    .as("json duplicate user should be present")
                    .doesNotContain("{\"user\":{}");
        }

        if (voteInfo.voteType == VoteInformation.Type.marthas) {
            assertThat(markdown)
                    .as("results should include a result table")
                    .contains("| Reaction |");
            assertThat(markdown)
                    .as("markdown should indicate it ignored extra reactions")
                    .contains("reactions were not counted");
            assertThat(json)
                    .as("json should also include ignored reactions")
                    .contains("\"ignored\":{\"reactions\":[\"rocket\"]");
        } else if (voteInfo.voteType == VoteInformation.Type.manualReactions) {
            assertThat(markdown)
                    .as("results should include a result table")
                    .contains("| Reaction |");
            assertThat(markdown)
                    .as("results should include a row for rockets")
                    .contains("| 🚀 |");
            assertThat(json)
                    .as("json should not have an ignored category")
                    .doesNotContain("\"ignored\":{");
        } else {
            assertThat(markdown)
                    .as("results should not include a result table")
                    .doesNotContain("| Reaction |");
        }

        return voteTally;
    }

    @Test
    void testQuorum() {
        // Test cases for different thresholds and group sizes
        assertAll("Threshold required votes",
                // Group size 3
                () -> assertEquals(3, VoteConfig.Threshold.all.requiredVotes(3), "All members required for group size 3"),
                () -> assertEquals(2, VoteConfig.Threshold.majority.requiredVotes(3), "Majority required for group size 3"),
                () -> assertEquals(2, VoteConfig.Threshold.twothirds.requiredVotes(3), "Two-thirds required for group size 3"),
                () -> assertEquals(3, VoteConfig.Threshold.fourfifths.requiredVotes(3),
                        "Four-fifths required for group size 3"),

                // Group size 5
                () -> assertEquals(5, VoteConfig.Threshold.all.requiredVotes(5), "All members required for group size 5"),
                () -> assertEquals(3, VoteConfig.Threshold.majority.requiredVotes(5), "Majority required for group size 5"),
                () -> assertEquals(4, VoteConfig.Threshold.twothirds.requiredVotes(5), "Two-thirds required for group size 5"),
                () -> assertEquals(4, VoteConfig.Threshold.fourfifths.requiredVotes(5),
                        "Four-fifths required for group size 5"),

                // Group size 10
                () -> assertEquals(10, VoteConfig.Threshold.all.requiredVotes(10), "All members required for group size 10"),
                () -> assertEquals(5, VoteConfig.Threshold.majority.requiredVotes(10), "Majority required for group size 10"),
                () -> assertEquals(7, VoteConfig.Threshold.twothirds.requiredVotes(10),
                        "Two-thirds required for group size 10"),
                () -> assertEquals(8, VoteConfig.Threshold.fourfifths.requiredVotes(10),
                        "Four-fifths required for group size 10"));
    }

    void setupMockAlternates(int hash, String primaryTeam, Map<String, DataActor> alternateLogins) {
        Alternates alts = new Alternates(hash, Map.of(primaryTeam, alternateLogins));
        VoteInformation.ALT_ACTORS.put("ALTS_" + repositoryId, alts);
    }
}
