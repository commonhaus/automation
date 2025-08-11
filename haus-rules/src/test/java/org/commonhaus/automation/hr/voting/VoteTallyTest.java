package org.commonhaus.automation.hr.voting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.hr.HausRulesTestBase;
import org.commonhaus.automation.hr.config.VoteConfig;
import org.commonhaus.automation.hr.config.VoteConfig.AlternateConfig;
import org.commonhaus.automation.hr.config.VoteConfig.AlternateDefinition;
import org.commonhaus.automation.hr.config.VoteConfig.TeamMapping;
import org.commonhaus.automation.hr.voting.VoteInformation.Alternates;
import org.commonhaus.automation.hr.voting.VoteTally.Category;
import org.commonhaus.automation.hr.voting.VoteTally.CountingMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.ReactionContent;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class VoteTallyTest extends HausRulesTestBase {
    static final String discussionId = "D_kwDOLDuJqs4AXteZ";

    static final Instant date = Instant.now();

    List<DataReaction> teamReactions = new ArrayList<>(51);
    Set<GHUser> teamUsers = new HashSet<>(51);
    List<DataReaction> unignore = new ArrayList<>(3);
    List<DataReaction> duplicates = new ArrayList<>(3);
    List<DataReaction> extraReactions = new ArrayList<>(10);

    @BeforeEach
    void setupVotes() throws Exception {
        setupDefaultMocks(TEST_ORG);

        for (int i = 1; i < 51; i++) {
            GHUser ghUser = mockUser("user" + i);
            DataActor user = new DataActor(ghUser);
            teamUsers.add(ghUser);

            // teamReactions.add(new DataReaction(user,
            //         i % 13 == 0 ? "rocket" : i % 3 == 0 ? "thumbs_down" : i % 2 == 0 ? "thumbs_up" : "eyes", date));
            mockReaction(teamReactions, user, i);
        }

        for (int i = 1; i < 11; i++) {
            DataActor user = new DataActor(mockUser("extra" + i));
            // extraReactions.add(new DataReaction(user,
            //         i % 4 == 0 ? "rocket" : i % 3 == 0 ? "thumbs_down" : i % 2 == 0 ? "thumbs_up" : "eyes", date));
            mockReaction(extraReactions, user, i);
        }

        // Create team with 50 members
        mockTeam("commonhaus/test-quorum-default", teamUsers);
    }

    @AfterEach
    void cleanupVotes() {
        teamReactions.clear();
        teamUsers.clear();
        unignore.clear();
        duplicates.clear();
        extraReactions.clear();
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

    @Test
    void testVoteMarthas() throws Exception {
        assertVoteTallyByMethod(CountingMethod.marthas);
    }

    @Test
    void testVoteManualReactions() throws Exception {
        assertVoteTallyByMethod(CountingMethod.manualReactions);
    }

    @Test
    void testVoteManualComments() throws Exception {
        assertVoteTallyByMethod(CountingMethod.manualComments);
    }

    @Test
    void testVoteMarthasMajority() throws Exception {
        extraReactions.addAll(unignore);
        extraReactions.addAll(duplicates);

        DataCommonItem mockItem = createItem(CountingMethod.marthas);
        ScopedQueryContext qc = new ScopedQueryContext(ctx, installationId, repoFullName);

        VoteConfig votingConfig = createVoteConfig(VoteConfig.Threshold.majority);
        VoteEvent event = createVoteEvent(votingConfig);

        VoteInformation voteInfo = new VoteInformation(ctx, qc, votingConfig, mockItem, event);
        assertVoteInformation(voteInfo, votingConfig, CountingMethod.marthas, false);

        assertVoteTally(26, true, voteInfo, extraReactions, teamReactions);
        assertVoteTally(23, false, voteInfo, extraReactions, teamReactions);
    }

    @Test
    void testVoteMarthasTwoThirds() throws Exception {
        extraReactions.addAll(unignore);
        extraReactions.addAll(duplicates);

        DataCommonItem mockItem = createItem(CountingMethod.marthas);
        ScopedQueryContext qc = new ScopedQueryContext(ctx, installationId, repoFullName);

        VoteConfig votingConfig = createVoteConfig(VoteConfig.Threshold.twothirds);
        VoteEvent event = createVoteEvent(votingConfig);

        VoteInformation voteInfo = new VoteInformation(ctx, qc, votingConfig, mockItem, event);
        assertVoteInformation(voteInfo, votingConfig, CountingMethod.marthas, false);

        assertVoteTally(34, true, voteInfo, extraReactions, teamReactions);
        assertVoteTally(33, false, voteInfo, extraReactions, teamReactions);
    }

    @Test
    void testVoteMarthasFourFifths() throws Exception {
        extraReactions.addAll(unignore);
        extraReactions.addAll(duplicates);

        DataCommonItem mockItem = createItem(CountingMethod.marthas);
        ScopedQueryContext qc = new ScopedQueryContext(ctx, installationId, repoFullName);

        VoteConfig votingConfig = createVoteConfig(VoteConfig.Threshold.fourfifths);
        VoteEvent event = createVoteEvent(votingConfig);

        VoteInformation voteInfo = new VoteInformation(ctx, qc, votingConfig, mockItem, event);
        assertVoteInformation(voteInfo, votingConfig, CountingMethod.marthas, false);

        assertVoteTally(40, true, voteInfo, extraReactions, teamReactions);
        assertVoteTally(39, false, voteInfo, extraReactions, teamReactions);
    }

    @Test
    void testAlternateVote() throws Exception {
        GHUser alternate = mockUser("alt_1");
        DataActor alternateUser = new DataActor(alternate);
        DataReaction altReaction = new DataReaction(alternateUser, "thumbs_down", date);

        mockTeam("commonhaus/test-quorum-seconds", Set.of(alternate));

        extraReactions.addAll(unignore);
        extraReactions.addAll(duplicates);

        // Remove reaction for user28
        teamReactions.removeIf(r -> r.user.login.equals("user28"));
        // add alternate/secondary user reaction
        extraReactions.add(altReaction);

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
                Map.of("user28", alternateUser));

        ScopedQueryContext qc = new ScopedQueryContext(ctx, installationId, repoFullName);

        VoteEvent event = createVoteEvent(votingConfig);

        DataCommonItem mockItem = createItem("""
                voting group: @commonhaus/test-quorum-default
                <!--vote::marthas approve="+1" ok="eyes" revise="-1" -->
                """);

        VoteInformation voteInfo = new VoteInformation(ctx, qc, votingConfig, mockItem, event);
        assertThat(voteInfo.alternates).isNotNull();

        // All have voted, but user28 vote is replaced by an alternate
        // secondary should be counted to meet quorum
        VoteTally voteTally = assertVoteTally(50, true, voteInfo, extraReactions, teamReactions);

        // primary rep is missing
        assertThat(voteTally.missingGroupActors).hasSize(1);

        // 1. Verify the alternate was promoted to the correct category
        // Assuming the alternate should be in the "revise" category:
        Category reviseCategory = voteTally.categories.get("revise");
        assertNotNull(reviseCategory, "Revise category should exist");
        assertEquals(16, reviseCategory.getTeamTotal(),
                "Revise category should have 16 team votes (including the alternate)");

        // Find the alternate vote in the team votes
        Optional<VoteRecord> alternateInTeam = reviseCategory.team.stream()
                .filter(VoteRecord::isAlternate)
                .filter(vr -> vr.login().equals("alt_1")) // Replace with your alternate's login
                .findFirst();

        assertThat(alternateInTeam).isPresent();
        assertThat(alternateInTeam.get().alternate).isTrue();

        // 4. Verify the markdown contains the alternate in the right place
        String[] markdown = voteTally.toMarkdown(false).split("\n");
        assertThat(markdown).anyMatch(line -> line.contains("alt_1") && line.contains("revise"));
        assertThat(markdown).noneMatch(line -> line.contains("alt_1") && !line.contains("revise"));
    }

    VoteConfig createVoteConfig(VoteConfig.Threshold threshold) {
        return createVoteConfig("commonhaus/test-quorum-default", threshold);
    }

    VoteConfig createVoteConfig(String teamName, VoteConfig.Threshold threshold) {
        VoteConfig votingConfig = new VoteConfig();
        votingConfig.voteThreshold = new java.util.HashMap<>();
        votingConfig.voteThreshold.put(teamName, threshold);
        votingConfig.excludeLogin = List.of("excluded");
        return votingConfig;
    }

    VoteEvent createVoteEvent(VoteConfig votingConfig) {
        return new VoteEvent(installationId, repoFullName, EventType.discussion, discussionId, 10);
    }

    DataCommonItem createItem(CountingMethod method) {
        switch (method) {
            case marthas:
                return createItem("""
                        voting group: @commonhaus/test-quorum-default
                        <!--vote::marthas approve="+1" ok="eyes" revise="-1" -->
                        """);
            case manualReactions:
                return createItem("""
                        voting group: @commonhaus/test-quorum-default
                        <!--vote::manual -->
                        """);
            case manualComments:
                return createItem("""
                        voting group: @commonhaus/test-quorum-default
                        <!--vote::manual comments -->
                        """);
            default:
                throw new IllegalArgumentException("Invalid method: " + method);
        }
    }

    void assertVoteTallyByMethod(CountingMethod method) throws Exception {
        VoteConfig votingConfig = createVoteConfig(VoteConfig.Threshold.all);
        VoteEvent event = createVoteEvent(votingConfig);

        DataCommonItem mockItem = createItem(method);
        ScopedQueryContext qc = new ScopedQueryContext(ctx, installationId, repoFullName);

        VoteInformation voteInfo = new VoteInformation(ctx, qc, votingConfig, mockItem, event);
        assertVoteInformation(voteInfo, votingConfig, method, false);

        VoteTally voteTally;

        // Martha's is the only method that ignores extra reactions due to specific defined categories.
        // Other methods will count all responses
        // @see #mockReaction for how these reactions are assigned
        if (method == CountingMethod.marthas) {
            voteTally = assertVoteTally(50, 47, false, voteInfo, extraReactions, teamReactions);
            assertThat(voteTally.missingGroupActors).size().isEqualTo(3);

            // Add extra votes that are within the martha's categories (should now be counted)
            extraReactions.addAll(unignore); // add 👀 in addition to 🚀
            // Add duplicate votes within martha's categories (count only one)
            extraReactions.addAll(duplicates); // add 👎 in addition to 👀
        }

        voteTally = assertVoteTally(50, 50, true, voteInfo, extraReactions, teamReactions);
        assertThat(voteTally.missingGroupActors).size().isEqualTo(0);

        switch (method) {
            case manualComments -> {
                assertThat(voteTally.categories.get("comment")).isNotNull();
            }
            case manualReactions -> {
                assertThat(voteTally.categories.get("👍")).isNotNull();
                assertThat(voteTally.categories.get("👀")).isNotNull();
                assertThat(voteTally.categories.get("🚀")).isNotNull();
            }
            case marthas -> {
                assertThat(voteTally.categories.get("approve")).isNotNull();
                assertThat(voteTally.categories.get("ok")).isNotNull();
                assertThat(voteTally.categories.get("revise")).isNotNull();
                assertThat(voteTally.categories.get("ignored")).isNotNull();
            }
            case undefined -> {

            }
            default -> {
                throw new IllegalArgumentException("Invalid method: " + method);
            }
        }
    }

    void assertVoteInformation(VoteInformation info, VoteConfig config, CountingMethod method, boolean alternates) {
        assertVoteInformation(info, config, method, alternates, "commonhaus/test-quorum-default");
    }

    void assertVoteInformation(VoteInformation info, VoteConfig config, CountingMethod method, boolean alternates,
            String teamName) {
        assertThat(info.isValid()).isTrue();
        assertThat(info.group).isEqualTo(teamName);
        assertThat(info.voteType).isEqualTo(method);

        VoteConfig.Threshold threshold = config.voteThreshold.get(teamName);
        assertThat(info.votingThreshold).isEqualTo(threshold);

        if (method == CountingMethod.marthas) {
            assertThat(info.approve).containsExactlyInAnyOrder(ReactionContent.PLUS_ONE);
            assertThat(info.ok).containsExactlyInAnyOrder(ReactionContent.EYES);
            assertThat(info.revise).containsExactlyInAnyOrder(ReactionContent.MINUS_ONE);
        }

        if (alternates) {
            assertThat(info.alternates).isNotNull();
        } else {
            assertThat(info.alternates).isNull();
        }

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
                        "categories"));

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

        if (voteTally.duplicates.isEmpty()) {
            assertThat(json)
                    .as("json duplicates should not be present when empty")
                    .doesNotContain("\"duplicates\":");
        } else {
            assertThat(json)
                    .as("json duplicates should be present")
                    .contains("\"duplicates\":");
        }

        if (voteTally.missingGroupActors.isEmpty()) {
            assertThat(json)
                    .as("json missingGroupActors user should not be present when empty")
                    .doesNotContain("\"missingGroupActors\":");
        } else {
            assertThat(json)
                    .as("json missingGroupActors user should be present")
                    .contains("\"missingGroupActors\":");
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

    void setupMockAlternates(int hash, String primaryTeam, Map<String, DataActor> alternateLogins) {
        Alternates alts = new Alternates(hash, Map.of(primaryTeam, alternateLogins));
        VoteQueryCache.ALT_ACTORS.put("ALTS_" + repositoryId, alts);
    }

    private void mockReaction(List<DataReaction> reactions, DataActor user, int i) {
        if (i % 13 == 0) {
            reactions.add(new DataReaction(user, "rocket", date));
            unignore.add(new DataReaction(user, "eyes", date));
        } else if (i % 19 == 0) {
            reactions.add(new DataReaction(user, "eyes", date));
            duplicates.add(new DataReaction(user, "thumbs_down", date));
        } else if (i % 3 == 0) {
            reactions.add(new DataReaction(user, "thumbs_down", date));
        } else if (i % 2 == 0) {
            reactions.add(new DataReaction(user, "thumbs_up", date));
        } else {
            reactions.add(new DataReaction(user, "eyes", date));
        }
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
