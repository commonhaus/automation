package org.commonhaus.automation.github.hr.voting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.commonhaus.automation.github.context.DataActor;
import org.commonhaus.automation.github.context.DataCommonComment;
import org.commonhaus.automation.github.context.DataCommonItem;
import org.commonhaus.automation.github.context.DataReaction;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.github.context.JsonAttribute;
import org.commonhaus.automation.github.hr.HausRulesTestBase;
import org.commonhaus.automation.github.hr.config.VoteConfig;
import org.commonhaus.automation.github.hr.config.VoteConfig.AlternateConfig;
import org.commonhaus.automation.github.hr.config.VoteConfig.AlternateDefinition;
import org.commonhaus.automation.github.hr.config.VoteConfig.TeamMapping;
import org.commonhaus.automation.github.hr.voting.VoteInformation.Alternates;
import org.commonhaus.automation.github.hr.voting.VoteTally.CountingMethod;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.ReactionContent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class VoteTallyTest extends HausRulesTestBase {
    static final String discussionId = "D_kwDOLDuJqs4AXteZ";

    ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testVoteTally() throws Exception {
        setupDefaultMocks(TEST_ORG);

        List<DataReaction> teamReactions = new ArrayList<>(51);
        Set<GHUser> teamUsers = new HashSet<>(51);
        List<DataReaction> unignore = new ArrayList<>(3);
        List<DataReaction> duplicates = new ArrayList<>(3);
        List<DataReaction> primary = new ArrayList<>(1);
        Date date = new Date();

        for (int i = 1; i < 51; i++) {
            GHUser ghUser = mockUser("user" + i);
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
        teamUsers.add(mockUser("excluded")); // should be excluded from totals

        List<DataReaction> extraReactions = new ArrayList<>(10);
        for (int i = 1; i < 11; i++) {
            DataActor user = new DataActor(mockUser("extra" + i));
            extraReactions.add(new DataReaction(user,
                    i % 4 == 0 ? "rocket" : i % 3 == 0 ? "thumbs_down" : i % 2 == 0 ? "thumbs_up" : "eyes", date));
        }

        GHUser alternate = mockUser("alt_1");
        DataActor alternateUser = new DataActor(alternate);
        extraReactions.add(new DataReaction(alternateUser, "thumbs_down", date)); // opposite of primary

        mockTeam("commonhaus/test-quorum-default", teamUsers);
        mockTeam("commonhaus/test-quorum-seconds", Set.of(alternate));

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

        ScopedQueryContext qc = new ScopedQueryContext(ctx, installationId, repoFullName);

        // Martha's

        VoteEvent event = createVoteEvent(votingConfig,
                "commonhaus/test-quorum-default",
                VoteConfig.Threshold.all, discussionId);

        DataCommonItem mockItem = createItem("""
                voting group: @commonhaus/test-quorum-default
                <!--vote::marthas approve="+1" ok="eyes" revise="-1" -->
                """);

        VoteInformation voteInfo = new VoteInformation(ctx, qc, votingConfig, mockItem, event);
        assertThat(voteInfo.isValid()).isTrue();
        assertThat(voteInfo.group).isEqualTo("commonhaus/test-quorum-default");
        assertThat(voteInfo.alternates).isNotNull();
        assertThat(voteInfo.votingThreshold).isEqualTo(VoteConfig.Threshold.all);
        assertThat(voteInfo.approve).containsExactlyInAnyOrder(ReactionContent.PLUS_ONE);
        assertThat(voteInfo.ok).containsExactlyInAnyOrder(ReactionContent.EYES);
        assertThat(voteInfo.revise).containsExactlyInAnyOrder(ReactionContent.MINUS_ONE);
        assertThat(voteInfo.voteType).isEqualTo(CountingMethod.marthas);

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
        voteInfo = new VoteInformation(ctx, qc, votingConfig, mockItem, event);
        assertVoteTally(26, 27, true, voteInfo, extraReactions, teamReactions); // alt_1 still included
        assertVoteTally(23, 24, false, voteInfo, extraReactions, teamReactions); // alt_1 still included

        // Supermajority (2/3) have voted

        votingConfig.voteThreshold.put("commonhaus/test-quorum-default", VoteConfig.Threshold.twothirds);
        voteInfo = new VoteInformation(ctx, qc, votingConfig, mockItem, event);
        assertVoteTally(34, true, voteInfo, extraReactions, teamReactions);
        assertVoteTally(33, false, voteInfo, extraReactions, teamReactions);

        // Manual (count reactions implied)

        mockItem = createItem("""
                voting group: @commonhaus/test-quorum-default
                <!--vote::manual -->
                """);

        event = createVoteEvent(votingConfig,
                "commonhaus/test-quorum-default", VoteConfig.Threshold.all,
                discussionId);

        voteInfo = new VoteInformation(ctx, qc, votingConfig, mockItem, event);
        assertThat(voteInfo.isValid()).isTrue();
        assertThat(voteInfo.approve).isEmpty();
        assertThat(voteInfo.ok).isEmpty();
        assertThat(voteInfo.revise).isEmpty();
        assertThat(voteInfo.voteType).isEqualTo(CountingMethod.manualReactions);

        voteTally = assertVoteTally(50, true, voteInfo, extraReactions, teamReactions);
        assertThat(voteTally.categories).hasSize(4);
        assertThat(voteTally.categories.get("👍")).isNotNull();
        assertThat(voteTally.categories.get("👀")).isNotNull();
        assertThat(voteTally.categories.get("👎")).isNotNull();
        assertThat(voteTally.categories.get("🚀")).isNotNull();
        assertVoteTally(49, false, voteInfo, extraReactions, teamReactions);

        // Manual: count comments instead of reactions

        mockItem = createItem("""
                voting group: @commonhaus/test-quorum-default
                <!--vote::manual comments -->
                """);

        event = createVoteEvent(votingConfig,
                "commonhaus/test-quorum-default", VoteConfig.Threshold.all,
                discussionId);

        voteInfo = new VoteInformation(ctx, qc, votingConfig, mockItem, event);
        assertThat(voteInfo.isValid()).isTrue();
        assertThat(voteInfo.approve).isEmpty();
        assertThat(voteInfo.ok).isEmpty();
        assertThat(voteInfo.revise).isEmpty();
        assertThat(voteInfo.voteType).isEqualTo(CountingMethod.manualComments);

        voteTally = assertVoteTally(50, true, voteInfo, extraReactions, teamReactions);
        assertThat(voteTally.categories).hasSize(1);
    }

    VoteEvent createVoteEvent(VoteConfig votingConfig, String teamName, VoteConfig.Threshold threshold, String nodeId) {
        votingConfig.voteThreshold.put(teamName, threshold);
        return new VoteEvent(installationId, repoFullName, EventType.discussion, nodeId, 10);
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

        if (voteInfo.voteType == CountingMethod.manualComments) {
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

        if (voteInfo.voteType == CountingMethod.marthas) {
            assertThat(markdown)
                    .as("results should include a result table")
                    .contains("| Reaction |");
            assertThat(markdown)
                    .as("markdown should indicate it ignored extra reactions")
                    .contains("reactions were not counted");
            assertThat(json)
                    .as("json should also include ignored reactions")
                    .contains("\"ignored\":{\"reactions\":[\"rocket\"]");
        } else if (voteInfo.voteType == CountingMethod.manualReactions) {
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
        VoteQueryCache.ALT_ACTORS.put("ALTS_" + repositoryId, alts);
    }

    DataCommonItem createItem(String body) {
        JsonObject item = Json.createObjectBuilder()
                .add("node", Json.createObjectBuilder()
                        .add("number", 1)
                        .add("id", discussionId)
                        .add("title", "Test Issue")
                        .add("closed", false)
                        .add("state", "open")
                        .add("isPullRequest", false)
                        .add("body", body)
                        .build())
                .build();
        return JsonAttribute.node.commonItemFrom(item);
    }
}
