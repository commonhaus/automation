package org.commonhaus.automation.hr.voting;

import static io.quarkiverse.githubapp.testing.GitHubAppTesting.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.nio.file.Path;
import java.util.Set;

import org.commonhaus.automation.github.context.BotComment;
import org.commonhaus.automation.github.context.DataLabel;
import org.commonhaus.automation.hr.HausRulesTestBase;
import org.commonhaus.automation.hr.config.HausRulesConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEvent;
import org.kohsuke.github.GHUser;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class VotingTest extends HausRulesTestBase {
    static final Set<DataLabel> REPO_LABELS = Set.of(
            VOTE_DONE, VOTE_OPEN, VOTE_QUORUM, VOTE_PROCEED, VOTE_REVISE);
    static final Set<DataLabel> ITEM_VOTE_OPEN = Set.of(VOTE_OPEN);
    static final String discussionId = "D_kwDOLDuJqs4AXteZ";

    @Override
    @BeforeEach
    protected void setupBase() throws Exception {
        super.setupBase();
        setLogin("commonhaus-test-bot");
    }

    @Override
    @AfterEach
    protected void waitForQueue() {
        super.waitForQueue();
        assertNoErrorEmails();
    }

    @Test
    void testDiscussionNoLabel() throws Exception {
        // repository label; no discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(discussionId, Set.of());

        // There is no vote/open label. Only the discussion should be queried
        given()
                .github(mocks -> {
                    mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-voting.yml");
                    setupGivenMocks(mocks, TEST_ORG);
                    mockTeams(hausMocks);

                    // Vote processing always starts fresh (query from item id)
                    setupGraphQLProcessing(mocks,
                            // initialize VoteProcessor + VoteInformation
                            QueryResponse.DISCUSSION_NO_GROUP);
                })
                .when().payloadFromClasspath("/github/eventDiscussionCreated.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    for (String cue : graphQueries) {
                        verify(mocks.installationGraphQLClient(installationId), timeout(500))
                                .executeSync(contains(cue), anyMap());
                    }

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });
    }

    @Test
    void testVoteOpenNoGroupNoMethod() throws Exception {
        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(discussionId, ITEM_VOTE_OPEN);

        given()
                .github(mocks -> {
                    mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-voting.yml");
                    setupGivenMocks(mocks, TEST_ORG);
                    mockTeams(hausMocks);

                    // Vote processing always starts fresh (query from item id)
                    setupGraphQLProcessing(mocks,
                            // initialize VoteProcessor + VoteInformation
                            QueryResponse.DISCUSSION_NO_GROUP_NO_METHOD,
                            // Count votes:
                            QueryResponse.NO_COMMENTS,
                            // confused reaction (missing group/method)
                            QueryResponse.MUTATE_ADD_CONFUSED_REACTION,
                            // add comment explaining things are missing
                            QueryResponse.MUTATE_ADD_DISCUSSION_COMMENT,
                            // update discussion with comment reference
                            QueryResponse.MUTATE_UPDATE_DISCUSSION);
                })
                .when().payloadFromClasspath("/github/eventDiscussionCreated.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    for (String cue : graphQueries) {
                        verify(mocks.installationGraphQLClient(installationId), timeout(500))
                                .executeSync(contains(cue), anyMap());
                    }

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });
        BotComment comment = verifyBotCommentCache(discussionId, botCommentId);
        assertThat(comment.getBody()).contains(
                "Configuration for item is invalid",
                "Team for specified group (",
                ") must exist (false)",
                "No valid vote counting method found");
    }

    @Test
    void testVoteOpenNoGroup() throws Exception {
        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(discussionId, ITEM_VOTE_OPEN);

        given()
                .github(mocks -> {
                    mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-voting.yml");
                    setupGivenMocks(mocks, TEST_ORG);
                    mockTeams(hausMocks);

                    // Vote processing always starts fresh (query from item id)
                    setupGraphQLProcessing(mocks,
                            // initialize VoteProcessor + VoteInformation
                            QueryResponse.DISCUSSION_NO_GROUP,
                            // Count votes:
                            QueryResponse.NO_COMMENTS,
                            // confused reaction (missing group)
                            QueryResponse.MUTATE_ADD_CONFUSED_REACTION,
                            // add comment explaining things are missing
                            QueryResponse.MUTATE_ADD_DISCUSSION_COMMENT,
                            // update discussion with comment reference
                            QueryResponse.MUTATE_UPDATE_DISCUSSION);
                })
                .when().payloadFromClasspath("/github/eventDiscussionCreated.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    for (String cue : graphQueries) {
                        verify(mocks.installationGraphQLClient(installationId), timeout(500))
                                .executeSync(contains(cue), anyMap());
                    }

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });
        BotComment comment = verifyBotCommentCache(discussionId, botCommentId);
        assertThat(comment.getBody()).contains(
                "Configuration for item is invalid",
                "Team for specified group (",
                ") must exist (false)");
        assertThat(comment.getBody()).doesNotContain("No valid vote counting method found");
    }

    @Test
    void testVoteOpenNoMethod() throws Exception {
        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(discussionId, ITEM_VOTE_OPEN);

        given()
                .github(mocks -> {
                    mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-voting.yml");
                    setupGivenMocks(mocks, TEST_ORG);
                    mockTeams(hausMocks);

                    // Vote processing always starts fresh (query from item id)
                    setupGraphQLProcessing(mocks,
                            // initialize VoteProcessor + VoteInformation
                            QueryResponse.DISCUSSION_NO_METHOD,
                            // Count votes:
                            QueryResponse.NO_COMMENTS,
                            // confused reaction (missing group)
                            QueryResponse.MUTATE_ADD_CONFUSED_REACTION,
                            // add comment explaining things are missing
                            QueryResponse.MUTATE_ADD_DISCUSSION_COMMENT,
                            // update discussion with comment reference
                            QueryResponse.MUTATE_UPDATE_DISCUSSION);
                })
                .when().payloadFromClasspath("/github/eventDiscussionCreated.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    for (String cue : graphQueries) {
                        verify(mocks.installationGraphQLClient(installationId), timeout(500))
                                .executeSync(contains(cue), anyMap());
                    }

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });
        BotComment comment = verifyBotCommentCache(discussionId, botCommentId);
        assertThat(comment.getBody()).contains(
                "Configuration for item is invalid",
                "No valid vote counting method found",
                "Team for specified group (",
                ") must exist (true)");
    }

    @Test
    void testVoteOpenValidVoteNoReactions() throws Exception {
        // Valid vote; no reactions

        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(discussionId, ITEM_VOTE_OPEN);

        given()
                .github(mocks -> {
                    mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-voting.yml");
                    setupGivenMocks(mocks, TEST_ORG);
                    mockTeams(hausMocks);

                    // Vote processing always starts fresh (query from item id)
                    setupGraphQLProcessing(mocks,
                            // initialize VoteProcessor + VoteInformation
                            QueryResponse.DISCUSSION_VALID,
                            // Count votes:
                            QueryResponse.NO_COMMENTS,
                            QueryResponse.NO_REACTIONS,
                            // Add bot comment with vote results
                            QueryResponse.MUTATE_ADD_DISCUSSION_COMMENT,
                            // Update discussion with comment reference
                            QueryResponse.MUTATE_UPDATE_DISCUSSION);
                })
                .when().payloadFromClasspath("/github/eventDiscussionCreated.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    for (String cue : graphQueries) {
                        verify(mocks.installationGraphQLClient(installationId), timeout(500))
                                .executeSync(contains(cue), anyMap());
                    }

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });
        BotComment comment = verifyBotCommentCache(discussionId, botCommentId);
        assertThat(comment.getBody()).contains(
                "No votes (non-bot reactions) found on this item.",
                "<!-- vote::data");
    }

    @Test
    void testVoteRemoveReactionQuorum() throws Exception {
        // Valid vote; left-over bot reaction

        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(discussionId, ITEM_VOTE_OPEN);

        given()
                .github(mocks -> {
                    mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-voting.yml");
                    setupGivenMocks(mocks, TEST_ORG);
                    mockTeams(hausMocks);

                    // Vote processing always starts fresh (query from item id)
                    setupGraphQLProcessing(mocks,
                            // initialize VoteProcessor + VoteInformation
                            QueryResponse.DISCUSSION_VALID,
                            // Count votes:
                            QueryResponse.NO_COMMENTS,
                            QueryResponse.MUTATE_REMOVE_CONFUSED_REACTION,
                            QueryResponse.ALL_TEAM_REACTIONS,
                            // Add bot comment with vote results
                            QueryResponse.MUTATE_ADD_DISCUSSION_COMMENT,
                            // Update discussion with comment reference
                            QueryResponse.MUTATE_UPDATE_DISCUSSION,
                            // add label for quorum
                            QueryResponse.MUTATE_ADD_LABEL_QUORUM);
                })
                .when().payloadFromClasspath("/github/eventDiscussionCreated.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    for (String cue : graphQueries) {
                        verify(mocks.installationGraphQLClient(installationId), timeout(500))
                                .executeSync(contains(cue), anyMap());
                    }

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });
        BotComment comment = verifyBotCommentCache(discussionId, botCommentId);
        assertThat(comment.getBody()).contains(
                "3 of 3 members of",
                "<!-- vote::data");
    }

    @Test
    void testVoteOpenValidVote() throws Exception {
        // Valid vote; only some team have voted + extra; update comment

        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(discussionId, ITEM_VOTE_OPEN);

        given()
                .github(mocks -> {
                    mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-voting.yml");
                    setupGivenMocks(mocks, TEST_ORG);
                    mockTeams(hausMocks);

                    // pre-cache bot comment
                    setupBotComment(discussionId);

                    // Vote processing always starts fresh (query from item id)
                    setupGraphQLProcessing(mocks,
                            // initialize VoteProcessor + VoteInformation
                            QueryResponse.DISCUSSION_VALID,
                            // Count votes:
                            QueryResponse.SOME_TEAM_REACTIONS,
                            // Update results
                            QueryResponse.MUTATE_UPDATE_DISCUSSION_COMMENT,
                            // Update discussion with comment reference
                            QueryResponse.MUTATE_UPDATE_DISCUSSION);
                })
                .when().payloadFromClasspath("/github/eventDiscussionCreated.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    for (String cue : graphQueries) {
                        verify(mocks.installationGraphQLClient(installationId), timeout(500))
                                .executeSync(contains(cue), anyMap());
                    }

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });
        BotComment comment = verifyBotCommentCache(discussionId, botCommentId);
        assertThat(comment.getBody()).contains(
                "2 of 3 members of",
                "<!-- vote::data");
    }

    @Test
    void testVoteOpenValidCommentsVote() throws Exception {
        // Valid vote; only some team have voted + extra; update comment

        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(discussionId, ITEM_VOTE_OPEN);

        given()
                .github(mocks -> {
                    mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-voting.yml");
                    setupGivenMocks(mocks, TEST_ORG);

                    GHUser user1 = mockUser("nmcl");
                    GHUser user2 = mockUser("evanchooly");
                    GHUser user3 = mockUser("kenfinnigan");
                    mockUser("ebullient");

                    mockTeams(hausMocks, user1, user2, user3);

                    // pre-cache bot comment
                    setupBotComment(discussionId);

                    // Vote processing always starts fresh (query from item id)
                    setupGraphQLProcessing(mocks,
                            // initialize VoteProcessor + VoteInformation
                            QueryResponse.DISCUSSION_VALID_COMMENTS,
                            // Count votes:
                            QueryResponse.VOTE_COMMENTS,
                            // Update results
                            QueryResponse.MUTATE_UPDATE_DISCUSSION_COMMENT,
                            // Update discussion with comment reference
                            QueryResponse.MUTATE_UPDATE_DISCUSSION,
                            // add label for quorum
                            QueryResponse.MUTATE_ADD_LABEL_QUORUM);
                })
                .when().payloadFromClasspath("/github/eventDiscussionCreated.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    for (String cue : graphQueries) {
                        verify(mocks.installationGraphQLClient(installationId), timeout(500))
                                .executeSync(contains(cue), anyMap());
                    }

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });
        BotComment comment = verifyBotCommentCache(discussionId, botCommentId);
        assertThat(comment.getBody()).contains(
                "3 of 3 members of",
                "<!-- vote::data");
    }

    @Test
    void testVoteOpenValidManualVote() throws Exception {
        // Valid vote; only some team have voted + extra; update comment

        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(discussionId, ITEM_VOTE_OPEN);

        given()
                .github(mocks -> {
                    mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-voting.yml");
                    setupGivenMocks(mocks, TEST_ORG);

                    GHUser user1 = mockUser("user1");
                    GHUser user2 = mockUser("user2");
                    GHUser user4 = mockUser("user4");
                    GHUser user5 = mockUser("user5");
                    mockUser("outsider");

                    mockTeams(hausMocks, user1, user2, user4, user5);

                    // pre-cache bot comment
                    setupBotComment(discussionId);

                    // Vote processing always starts fresh (query from item id)
                    setupGraphQLProcessing(mocks,
                            // initialize VoteProcessor + VoteInformation
                            QueryResponse.DISCUSSION_VALID_MANUAL,
                            // Count votes:
                            QueryResponse.SOME_TEAM_REACTIONS,
                            // Update results
                            QueryResponse.MUTATE_UPDATE_DISCUSSION_COMMENT,
                            // Update discussion with comment reference
                            QueryResponse.MUTATE_UPDATE_DISCUSSION,
                            // Add quorum label
                            QueryResponse.MUTATE_ADD_LABEL_QUORUM);
                })
                .when().payloadFromClasspath("/github/eventDiscussionCreated.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    for (String cue : graphQueries) {
                        verify(mocks.installationGraphQLClient(installationId), timeout(500))
                                .executeSync(contains(cue), anyMap());
                    }

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });
        BotComment comment = verifyBotCommentCache(discussionId, botCommentId);
        assertThat(comment.getBody()).contains(
                "4 of 4 members of",
                "<!-- vote::data",
                "Additional input (🙏 🥰 🙌):",
                "outsider");
    }

    @Test
    public void testManualResultsComment() throws Exception {
        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(discussionId, ITEM_VOTE_OPEN);

        given()
                .github(mocks -> {
                    mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-voting.yml");
                    setupGivenMocks(mocks, TEST_ORG);

                    GHUser user1 = mockUser("nmcl");
                    GHUser user2 = mockUser("evanchooly");
                    GHUser user3 = mockUser("kenfinnigan");
                    GHUser ebullient = mockUser("ebullient");

                    mockTeams(hausMocks, user1, user2, user3, ebullient);

                    // pre-cache bot comment
                    setupBotComment(discussionId);

                    // Vote processing always starts fresh (query from item id)
                    // So there is a comment that comes in w/ the payload (...CommentCreated.VoteResult.json)
                    // but what is actually tested is the comments fetched by VOTE_RESULT_COMMENT
                    setupGraphQLProcessing(mocks,
                            // initialize VoteProcessor + VoteInformation
                            QueryResponse.DISCUSSION_VALID,
                            // Count votes:
                            QueryResponse.MANUAL_RESULT_COMMENT,
                            QueryResponse.NO_REACTIONS,
                            // Update results
                            QueryResponse.MUTATE_UPDATE_DISCUSSION_COMMENT,
                            // Update discussion with comment reference
                            QueryResponse.MUTATE_UPDATE_DISCUSSION,
                            // Add done, remove open
                            QueryResponse.MUTATE_REMOVE_LABEL_OPEN,
                            QueryResponse.MUTATE_ADD_LABEL_DONE);
                })
                .when().payloadFromClasspath("/github/eventDiscussionCommentCreated.VoteResult.json")
                .event(GHEvent.DISCUSSION_COMMENT)
                .then().github(mocks -> {
                    for (String cue : graphQueries) {
                        verify(mocks.installationGraphQLClient(installationId), timeout(500))
                                .executeSync(contains(cue), anyMap());
                    }

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });
        BotComment comment = verifyBotCommentCache(discussionId, botCommentId);
        assertThat(comment.getBody()).contains(
                "This vote has been [closed]",
                "<!-- vote::data");
    }

    @Test
    public void testIgnoredManualResultsComment() throws Exception {
        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(discussionId, ITEM_VOTE_OPEN);

        given()
                .github(mocks -> {
                    mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-voting.yml");
                    setupGivenMocks(mocks, TEST_ORG);

                    GHUser user1 = mockUser("nmcl");
                    GHUser user2 = mockUser("evanchooly");
                    GHUser user3 = mockUser("kenfinnigan");

                    mockTeams(hausMocks, user1, user2, user3);

                    // pre-cache bot comment
                    setupBotComment(discussionId);

                    // Vote processing always starts fresh (query from item id)
                    // So there is a comment that comes in w/ the payload (...CommentCreated.VoteResult.json)
                    // but what is actually tested is the comments fetched by VOTE_RESULT_COMMENT
                    setupGraphQLProcessing(mocks,
                            // initialize VoteProcessor + VoteInformation
                            QueryResponse.DISCUSSION_VALID,
                            // Count votes:
                            QueryResponse.NO_REACTIONS,
                            // Update results
                            QueryResponse.MUTATE_UPDATE_DISCUSSION_COMMENT,
                            // Update discussion with comment reference
                            QueryResponse.MUTATE_UPDATE_DISCUSSION);
                })
                .when().payloadFromClasspath("/github/eventDiscussionCommentCreated.VoteResult.json")
                .event(GHEvent.DISCUSSION_COMMENT)
                .then().github(mocks -> {
                    for (String cue : graphQueries) {
                        verify(mocks.installationGraphQLClient(installationId), timeout(500))
                                .executeSync(contains(cue), anyMap());
                    }

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });
        BotComment comment = verifyBotCommentCache(discussionId, botCommentId);
        assertThat(comment.getBody()).contains(
                "<!-- vote::data");
        assertThat(comment.getBody()).doesNotContain("This vote has been [closed]");
    }

    @Test
    void testVoteOpenPullRequest() throws Exception {
        String pullRequestId = "PR_kwDOLDuJqs5mlMVl";

        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(pullRequestId, ITEM_VOTE_OPEN);

        given()
                .github(mocks -> {
                    mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-voting.yml");
                    setupGivenMocks(mocks, TEST_ORG);
                    mockTeams(hausMocks);

                    // pre-cache bot comment
                    setupBotComment(pullRequestId);

                    // Vote processing always starts fresh (query from item id)
                    setupGraphQLProcessing(mocks,
                            // initialize VoteProcessor + VoteInformation
                            QueryResponse.PULL_REQUEST,
                            // Count votes:
                            QueryResponse.SOME_TEAM_REACTIONS,
                            QueryResponse.PULL_REQUEST_REVIEWS,
                            // Update results
                            QueryResponse.MUTATE_UPDATE_ISSUE_COMMENT,
                            // Update pull request with comment reference
                            QueryResponse.MUTATE_UPDATE_PULL_REQUEST);
                })
                .when().payloadFromClasspath("/github/eventPullRequestReviewRequested.json")
                .event(GHEvent.PULL_REQUEST)
                .then().github(mocks -> {
                    for (String cue : graphQueries) {
                        verify(mocks.installationGraphQLClient(installationId), timeout(500))
                                .executeSync(contains(cue), anyMap());
                    }

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });
        BotComment comment = verifyBotCommentCache(pullRequestId, botCommentId);
        assertThat(comment.getBody()).contains(
                "Additional input (🙏 🥰 🙌):",
                "outsider",
                "Common title",
                "<!-- vote::data");
    }

    @Test
    public void testPullRequestClosedVoteResult() throws Exception {
        String pullRequestId = "PR_kwDOLDuJqs5mDkwX";

        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(pullRequestId, ITEM_VOTE_OPEN);

        given()
                .github(mocks -> {
                    mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-voting.yml");
                    setupGivenMocks(mocks, TEST_ORG);

                    GHUser user1 = mockUser("commonhaus-bot");
                    GHUser user2 = mockUser("ebullient");

                    mockTeams(hausMocks, user1, user2);
                    setupBotComment(pullRequestId);

                    // Vote processing always starts fresh (query from item id)
                    setupGraphQLProcessing(mocks,
                            // initialize VoteProcessor + VoteInformation
                            QueryResponse.PULL_REQUEST_CLOSED,
                            // Count votes:
                            QueryResponse.SOME_TEAM_REACTIONS,
                            QueryResponse.PULL_REQUEST_REVIEWS,
                            QueryResponse.MANUAL_RESULT_COMMENT,
                            // Update results
                            QueryResponse.MUTATE_UPDATE_ISSUE_COMMENT,
                            // Update pull request with comment reference
                            QueryResponse.MUTATE_UPDATE_PULL_REQUEST,
                            // Change from open to done
                            QueryResponse.MUTATE_REMOVE_LABEL_OPEN,
                            QueryResponse.MUTATE_ADD_LABEL_DONE);
                })
                .when().payloadFromClasspath("/github/eventPullRequestClosed.json")
                .event(GHEvent.PULL_REQUEST)
                .then().github(mocks -> {
                    for (String cue : graphQueries) {
                        verify(mocks.installationGraphQLClient(installationId), timeout(500))
                                .executeSync(contains(cue), anyMap());
                    }

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
    public void testPullRequestOpenVoteResult() throws Exception {
        String pullRequestId = "PR_kwDOLDuJqs5mlMVl";

        // repository and discussion label
        setLabels(repositoryId, REPO_LABELS);
        setLabels(pullRequestId, ITEM_VOTE_OPEN);

        given()
                .github(mocks -> {
                    mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-voting.yml");
                    setupGivenMocks(mocks, TEST_ORG);

                    GHUser user1 = mockUser("commonhaus-bot");
                    GHUser user2 = mockUser("ebullient");

                    mockTeams(hausMocks, user1, user2);
                    setupBotComment(pullRequestId);

                    // Vote processing always starts fresh (query from item id)
                    // So there is a comment that comes in w/ the payload (...CommentCreated.VoteResult.json)
                    // but what is actually tested is the comments fetched by VOTE_RESULT_COMMENT
                    setupGraphQLProcessing(mocks,
                            // initialize VoteProcessor + VoteInformation
                            QueryResponse.PULL_REQUEST,
                            // Count votes:
                            QueryResponse.MANUAL_RESULT_COMMENT,
                            QueryResponse.SOME_TEAM_REACTIONS,
                            QueryResponse.PULL_REQUEST_REVIEWS,
                            // Update results
                            QueryResponse.MUTATE_UPDATE_ISSUE_COMMENT,
                            // Update discussion with comment reference
                            QueryResponse.MUTATE_UPDATE_PULL_REQUEST,
                            // Add done, remove open
                            QueryResponse.MUTATE_REMOVE_LABEL_OPEN,
                            QueryResponse.MUTATE_ADD_LABEL_DONE);
                })
                .when().payloadFromClasspath("/github/eventIssueCommentCreated.json")
                .event(GHEvent.ISSUE_COMMENT)
                .then().github(mocks -> {
                    for (String cue : graphQueries) {
                        verify(mocks.installationGraphQLClient(installationId), timeout(500))
                                .executeSync(contains(cue), anyMap());
                    }

                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });

        BotComment botComment = verifyBotCommentCache(pullRequestId, botCommentId);
        assertThat(botComment.getBody()).contains(
                "This vote has been [closed]",
                "Additional input (🙏 🥰 🙌):",
                "outsider");
    }

    enum QueryResponse implements MockResponse {
        DISCUSSION_VALID("query($id: ID!) {",
                "src/test/resources/github/queryDiscussion.methodMarthas.json"),
        DISCUSSION_VALID_COMMENTS("query($id: ID!) {",
                "src/test/resources/github/queryDiscussion.methodComments.json"),
        DISCUSSION_VALID_MANUAL("query($id: ID!) {",
                "src/test/resources/github/queryDiscussion.methodManual.json"),
        DISCUSSION_NO_GROUP("query($id: ID!) {",
                "src/test/resources/github/queryDiscussion.noGroup.json"),
        DISCUSSION_NO_METHOD("query($id: ID!) {",
                "src/test/resources/github/queryDiscussion.noMethod.json"),
        DISCUSSION_NO_GROUP_NO_METHOD("query($id: ID!) {",
                "src/test/resources/github/queryDiscussion.noGroupNoMethod.json"),

        PULL_REQUEST("query($id: ID!) {",
                "src/test/resources/github/queryPullRequest.json"),
        PULL_REQUEST_CLOSED("query($id: ID!) {",
                "src/test/resources/github/queryPullRequestClosed.json"),

        PULL_REQUEST_REVIEWS("latestReviews(first: 100",
                "src/test/resources/github/queryPullRequestReviews.json"),

        NO_COMMENTS("query($itemId: ID!, $after: String)",
                "src/test/resources/github/queryComments.None.json"),
        BOT_COMMENT("query($itemId: ID!, $after: String)",
                "src/test/resources/github/queryComments.Bot.json"),
        MANUAL_RESULT_COMMENT("query($itemId: ID!, $after: String)",
                "src/test/resources/github/queryComments.ManualVoteResult.json"),
        VOTE_COMMENTS("query($itemId: ID!, $after: String)",
                "src/test/resources/github/queryComments.VoteComments.json"),
        VOTE_RESULT_COMMENT("query($itemId: ID!, $after: String)",
                "src/test/resources/github/queryComments.ManualVoteResult.json"),

        NO_REACTIONS("reactions(first: 100",
                "src/test/resources/github/queryReactions.None.json"),
        ALL_TEAM_REACTIONS("reactions(first: 100",
                "src/test/resources/github/queryReactions.AllTeam.json"),
        SOME_TEAM_REACTIONS("reactions(first: 100",
                "src/test/resources/github/queryReactions.SomeTeam.json"),

        MUTATE_ADD_CONFUSED_REACTION("addReaction(",
                "src/test/resources/github/mutableAddReaction.Confused.json"),
        MUTATE_REMOVE_CONFUSED_REACTION("RemoveReaction(",
                "src/test/resources/github/mutableRemoveReaction.Confused.json"),

        MUTATE_ADD_DISCUSSION_COMMENT("addDiscussionComment(",
                "src/test/resources/github/mutableAddDiscussionComment.json"),
        MUTATE_UPDATE_DISCUSSION_COMMENT("updateDiscussionComment(",
                "src/test/resources/github/mutableUpdateDiscussionComment.json"),
        MUTATE_UPDATE_ISSUE_COMMENT("updateIssueComment(",
                "src/test/resources/github/mutableUpdateIssueComment.json"),

        MUTATE_UPDATE_DISCUSSION("updateDiscussion(",
                "src/test/resources/github/mutableUpdateDiscussion.json"),
        MUTATE_UPDATE_PULL_REQUEST("updatePullRequest(",
                "src/test/resources/github/mutableUpdatePullRequest.json"),

        MUTATE_ADD_LABEL_DONE("addLabelsToLabelable(",
                "src/test/resources/github/mutableAddLabelsToLabelable.VotingDone.json"),
        MUTATE_ADD_LABEL_QUORUM("addLabelsToLabelable(",
                "src/test/resources/github/mutableAddLabelsToLabelable.VotingQuorum.json"),
        MUTATE_REMOVE_LABEL_OPEN("removeLabelsFromLabelable(",
                "src/test/resources/github/mutableRemoveLabelsFromLabelable.json"),
                ;

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
            // consistent with the installationId used in the tests
            return installationId;
        }
    }
}
