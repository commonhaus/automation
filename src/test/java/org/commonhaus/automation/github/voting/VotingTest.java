package org.commonhaus.automation.github.voting;

import static io.quarkiverse.githubapp.testing.GitHubAppTesting.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import jakarta.json.Json;

import org.commonhaus.automation.github.EventData;
import org.commonhaus.automation.github.RepositoryAppConfig;
import org.commonhaus.automation.github.Voting;
import org.commonhaus.automation.github.model.DataActor;
import org.commonhaus.automation.github.model.DataCommonComment;
import org.commonhaus.automation.github.model.DataLabel;
import org.commonhaus.automation.github.model.DataReaction;
import org.commonhaus.automation.github.model.EventQueryContext;
import org.commonhaus.automation.github.model.GithubTest;
import org.commonhaus.automation.github.model.QueryCache;
import org.commonhaus.automation.github.model.QueryHelper.QueryContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEvent;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHTeam;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.ReactionContent;
import org.mockito.Mockito;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

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

    ObjectMapper objectMapper = new ObjectMapper();

    void setupTeamReactions(GitHubMockSetupContext mocks) throws IOException {
        GHUser user1 = mockGHUser("user1");
        GHUser user2 = mockGHUser("user2");
        GHUser user3 = mockGHUser("user3");
        mockGHUser("user4");

        var team = mocks.ghObject(GHTeam.class, 1);
        when(team.getMembers())
                .thenReturn(Set.of(user1, user2, user3));

        QueryCache.TEAM.putCachedValue("commonhaus/test-quorum-default", team);
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
    void testVoteOpenMarthas() throws Exception {
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
    void testVoteOpenValidVoteRemoveReaction() throws Exception {
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
    void testVoteOpenValidVote() throws Exception {
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

    @Test
    void testVoteTally() throws JsonProcessingException {
        String body = """
                voting group: @commonhaus/test-quorum-default
                <!--vote::marthas approve="+1" ok="eyes" revise="-1" -->
                """;

        GHOrganization org = Mockito.mock(GHOrganization.class);
        when(org.getLogin()).thenReturn("commonhaus");

        GHTeam team = Mockito.mock(GHTeam.class);
        QueryCache.TEAM.putCachedValue("commonhaus/test-quorum-default", team);

        List<DataReaction> teamReactions = new ArrayList<>(50);
        List<DataActor> teamLogins = new ArrayList<>(50);
        for (int i = 1; i < 51; i++) {
            DataActor user = new DataActor(mockGHUser("user" + i));
            teamLogins.add(user);
            teamReactions.add(new DataReaction(user,
                    i % 13 == 0 ? "rocket" : i % 3 == 0 ? "thumbs_down" : i % 2 == 0 ? "thumbs_up" : "eyes"));
        }
        List<DataReaction> extraReactions = new ArrayList<>(10);
        for (int i = 1; i < 11; i++) {
            DataActor user = new DataActor(mockGHUser("extra" + i));
            extraReactions.add(new DataReaction(user,
                    i % 4 == 0 ? "rocket" : i % 3 == 0 ? "thumbs_down" : i % 2 == 0 ? "thumbs_up" : "eyes"));
        }

        EventQueryContext queryContext = Mockito.mock(EventQueryContext.class);
        when(queryContext.getOrganization()).thenReturn(org);

        Voting.Config votingConfig = new Voting.Config();
        votingConfig.votingThreshold = new java.util.HashMap<>();
        votingConfig.votingThreshold.put("commonhaus/test-quorum-default", Voting.Threshold.all);

        VoteEvent event = createVoteEvent(queryContext, votingConfig, "commonhaus/test-quorum-default", Voting.Threshold.all,
                body);

        // Martha's

        VoteInformation voteInfo = new VoteInformation(event);
        assertThat(voteInfo.isValid()).isTrue();
        assertThat(voteInfo.group).isEqualTo("commonhaus/test-quorum-default");
        assertThat(voteInfo.votingThreshold).isEqualTo(Voting.Threshold.all);
        assertThat(voteInfo.approve).containsExactlyInAnyOrder(ReactionContent.PLUS_ONE);
        assertThat(voteInfo.ok).containsExactlyInAnyOrder(ReactionContent.EYES);
        assertThat(voteInfo.revise).containsExactlyInAnyOrder(ReactionContent.MINUS_ONE);
        assertThat(voteInfo.voteType).isEqualTo(VoteInformation.Type.marthas);

        // All have voted

        VoteTally voteTally = assertVoteTally(50, true, voteInfo, extraReactions, teamReactions, teamLogins);
        assertThat(voteTally.categories.get("approve")).isNotNull();
        assertThat(voteTally.categories.get("ok")).isNotNull();
        assertThat(voteTally.categories.get("revise")).isNotNull();
        assertThat(voteTally.categories.get("ignored")).isNotNull();
        assertThat(voteTally.missingGroupActors).isEmpty();
        assertVoteTally(49, false, voteInfo, extraReactions, teamReactions, teamLogins);

        // Majority have voted

        votingConfig.votingThreshold.put("commonhaus/test-quorum-default", Voting.Threshold.majority);
        voteInfo = new VoteInformation(event);
        assertVoteTally(26, true, voteInfo, extraReactions, teamReactions, teamLogins);
        assertVoteTally(25, false, voteInfo, extraReactions, teamReactions, teamLogins);

        // Supermajority (2/3) have voted

        votingConfig.votingThreshold.put("commonhaus/test-quorum-default", Voting.Threshold.supermajority);
        voteInfo = new VoteInformation(event);
        assertVoteTally(34, true, voteInfo, extraReactions, teamReactions, teamLogins);
        assertVoteTally(33, false, voteInfo, extraReactions, teamReactions, teamLogins);

        // Manual (count reactions implied)

        body = """
                voting group: @commonhaus/test-quorum-default
                <!--vote::manual -->
                """;

        event = createVoteEvent(queryContext, votingConfig, "commonhaus/test-quorum-default", Voting.Threshold.all, body);

        voteInfo = new VoteInformation(event);
        assertThat(voteInfo.isValid()).isTrue();
        assertThat(voteInfo.approve).isEmpty();
        assertThat(voteInfo.ok).isEmpty();
        assertThat(voteInfo.revise).isEmpty();
        assertThat(voteInfo.voteType).isEqualTo(VoteInformation.Type.manualReactions);

        voteTally = assertVoteTally(50, true, voteInfo, extraReactions, teamReactions, teamLogins);
        assertThat(voteTally.categories).hasSize(4);
        assertThat(voteTally.categories.get("👍")).isNotNull();
        assertThat(voteTally.categories.get("👀")).isNotNull();
        assertThat(voteTally.categories.get("👎")).isNotNull();
        assertThat(voteTally.categories.get("🚀")).isNotNull();
        assertVoteTally(49, false, voteInfo, extraReactions, teamReactions, teamLogins);

        // Manual: count comments instead of reactions

        body = """
                voting group: @commonhaus/test-quorum-default
                <!--vote::manual comments -->
                """;

        event = createVoteEvent(queryContext, votingConfig, "commonhaus/test-quorum-default", Voting.Threshold.all, body);

        voteInfo = new VoteInformation(event);
        assertThat(voteInfo.isValid()).isTrue();
        assertThat(voteInfo.approve).isEmpty();
        assertThat(voteInfo.ok).isEmpty();
        assertThat(voteInfo.revise).isEmpty();
        assertThat(voteInfo.voteType).isEqualTo(VoteInformation.Type.manualComments);

        voteTally = assertVoteTally(50, true, voteInfo, extraReactions, teamReactions, teamLogins);
        assertThat(voteTally.categories).hasSize(1);
    }

    VoteEvent createVoteEvent(QueryContext queryContext, Voting.Config votingConfig, String group, Voting.Threshold threshold,
            String body) {
        EventData eventData = Mockito.mock(EventData.class);
        when(eventData.getBody()).thenReturn(body);

        votingConfig.votingThreshold.put("commonhaus/test-quorum-default", Voting.Threshold.all);

        return new VoteEvent(queryContext, votingConfig, eventData);
    }

    VoteTally assertVoteTally(int numUsers, boolean expectHasQuorum,
            VoteInformation voteInfo,
            List<DataReaction> extraReactions,
            List<DataReaction> teamReactions,
            List<DataActor> teamLogins) throws JsonProcessingException {

        List<DataReaction> reactions = Stream
                .concat(teamReactions.stream().limit(numUsers), extraReactions.stream())
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

        VoteTally voteTally = new VoteTally(voteInfo, reactions, comments, teamLogins);

        String json = objectMapper.writeValueAsString(voteTally);
        //System.out.println(json);
        assertThat(json)
                .contains(List.of("hasQuorum", "votingThreshold",
                        "group", "groupSize", "groupVotes", "countedVotes", "droppedVotes",
                        "categories", "duplicates", "missingGroupActors"));

        String markdown = voteTally.toMarkdown();
        assertThat(markdown)
                .as("should contain number of team member votes")
                .contains(numUsers + " of " + teamLogins.size() + " members");

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
}
