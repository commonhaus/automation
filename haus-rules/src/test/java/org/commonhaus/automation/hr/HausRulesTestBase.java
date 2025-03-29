package org.commonhaus.automation.hr;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Stream;

import jakarta.inject.Inject;
import jakarta.json.Json;

import org.commonhaus.automation.github.context.BaseQueryCache;
import org.commonhaus.automation.github.context.BotComment;
import org.commonhaus.automation.github.context.ContextHelper;
import org.commonhaus.automation.github.context.DataCommonComment;
import org.commonhaus.automation.github.context.DataLabel;
import org.commonhaus.automation.hr.voting.VoteQueryCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHPersonSet;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.githubapp.testing.dsl.GitHubMockVerificationContext;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Base test class for all Haus Rules tests that provides common test utilities
 * and extends the common module's ContextHelper
 */
@QuarkusTest
public abstract class HausRulesTestBase extends ContextHelper {
    public static final String repoFullName = "commonhaus/automation-test";
    public static final String repositoryId = "R_kgDOLDuJqg";
    public static final long repoId = 742099370;
    public static final long installationId = 46053716;
    public static final long organizationId = 144493209;

    protected static final ObjectMapper objectMapper = new ObjectMapper();

    protected static final DefaultValues TEST_ORG = new DefaultValues(
            installationId, // installationId
            new Resource(organizationId, "commonhaus"), // organization
            new Resource(repoId, repositoryId, repoFullName), // repository
            false // do not mock query context
    );

    // Common label definitions used across tests
    protected static final DataLabel BUG = new DataLabel.Builder()
            .id("LA_kwDOLDuJqs8AAAABfqsdNQ")
            .name("bug").build();

    protected static final DataLabel NOTICE = new DataLabel.Builder()
            .id("LA_kwDOLDuJqs8AAAABgn2hGA")
            .name("notice").build();

    protected static final DataLabel VOTE_OPEN = new DataLabel.Builder()
            .id("LA_kwDOKRPTI88AAAABgkXEVQ")
            .name("vote/open").build();

    public static final DataLabel VOTE_PROCEED = new DataLabel.Builder()
            .name("vote/proceed").build();
    public static final DataLabel VOTE_QUORUM = new DataLabel.Builder()
            .name("vote/quorum").build();
    public static final DataLabel VOTE_REVISE = new DataLabel.Builder()
            .name("vote/revise").build();
    public static final DataLabel VOTE_DONE = new DataLabel.Builder()
            .id("LA_kwDOKRPTI88AAAABhGp_7g")
            .name("vote/done").build();

    public static final String botCommentId = "DC_kwDOLDuJqs4Agx94";
    public static final Integer botCommentDatabaseId = 8593272;

    @Inject
    protected AppContextService ctx;

    @BeforeEach
    protected void setupBase() throws Exception {
        // Reset before each test
        reset();
        Stream.of(VoteQueryCache.values()).forEach(v -> v.invalidateAll());
    }

    @AfterEach
    protected void waitForQueue() {
        // Make sure queue is drained between tests
        await().atMost(5, SECONDS).until(() -> updateQueue.isEmpty());
    }

    /**
     * Verify that a BotComment exists in the cache for the given node ID
     */
    protected BotComment verifyBotComment(String nodeId, String commentId) {
        await().atMost(5, SECONDS)
                .until(() -> BaseQueryCache.RECENT_BOT_CONTENT.get(nodeId) != null);

        BotComment botComment = BaseQueryCache.RECENT_BOT_CONTENT.get(nodeId);
        assertThat(botComment.getCommentId()).isEqualTo(commentId);
        return botComment;
    }

    /**
     * Verify the presence and content of labels in the cache
     */
    protected void verifyLabels(String labeledId, int expectedSize, String... expectedLabels) {
        Collection<DataLabel> labels = BaseQueryCache.LABELS.get(labeledId);
        assertThat(labels).isNotNull();
        assertThat(labels).hasSize(expectedSize);

        if (expectedSize > 0 && expectedLabels != null) {
            for (String expectedLabel : expectedLabels) {
                assertThat(labels.stream().map(l -> l.name)).contains(expectedLabel);
            }
        }
    }

    /**
     * Verify no label cache exists for the given ID
     */
    protected void verifyNoLabels(String labeledId) {
        Collection<DataLabel> labels = BaseQueryCache.LABELS.get(labeledId);
        assertThat(labels).isNull();
    }

    public void setLogin(String login) {
        BaseQueryCache.BOT_LOGIN.put("" + installationId, login);
    }

    public void mockAuthor(MockInstallation install, boolean inOrg, boolean inTeam) throws Exception {
        mockAuthor(inOrg, inTeam, install.github());
    }

    public void mockAuthor(boolean inOrg, boolean inTeam, GitHub gh) throws Exception {
        GHUser user = mockUser("ebullient");

        GHOrganization org = mockOrganization("commonhaus", gh);
        when(org.hasMember(user)).thenReturn(inOrg);

        if (inOrg) {
            @SuppressWarnings("unchecked")
            GHPersonSet<GHOrganization> orgs = mock(GHPersonSet.class);
            when(orgs.iterator()).thenReturn(Set.of(org).iterator());
            when(user.getOrganizations()).thenReturn(orgs);
        }
        if (inTeam) {
            mockTeam("commonhaus/test-quorum-default", Set.of(user));
        }
    }

    public void verifyOrganizationMember(GitHubMockVerificationContext mocks, MockInstallation install) throws Exception {
        verifyOrganizationMember(mocks, install.github());
    }

    public void verifyOrganizationMember(GitHubMockVerificationContext mocks, GitHub gh) throws Exception {
        GHOrganization org = mockOrganization("commonhaus", gh);
        GHUser ebullient = mockUser("ebullient", gh);
        verify(org).hasMember(ebullient);
    }

    public void mockTeams(MockInstallation install, GHUser... users) throws Exception {
        mockTeams(install.github(), users);
    }

    public void mockTeams(GitHub gh, GHUser... users) throws Exception {
        mockUser("user4", gh);

        if (users.length == 0) {
            GHUser user1 = mockUser("user1", gh);
            GHUser user2 = mockUser("user2", gh);
            GHUser user3 = mockUser("user3", gh);
            mockTeam("commonhaus/test-quorum-default", Set.of(user1, user2, user3));
        } else {
            mockTeam("commonhaus/test-quorum-default", Set.of(users));
        }

        GHUser second = mockUser("second", gh);
        mockTeam("commonhaus/test-quorum-seconds", Set.of(second));

        GHRepository foundationRepo = mockRepository("commonhaus/foundation", gh);
        mockFileContent(foundationRepo,
                "CONTACTS.yaml", "src/test/resources/CONTACTS.yaml");
    }

    public void setupBotComment(String nodeId) {
        DataCommonComment comment = new DataCommonComment(
                Json.createObjectBuilder()
                        .add("id", botCommentId)
                        .add("databaseId", botCommentDatabaseId)
                        .add("url",
                                "https://github.com/commonhaus/automation-test/discussions/25#discussioncomment-8593272")
                        .add("body", "This is a test comment")
                        .add("author", Json.createObjectBuilder()
                                .add("login", "commonhaus-test-bot")
                                .build())
                        .build());

        createBotComment(nodeId, comment);
    }

    public BotComment verifyBotCommentCache(String nodeId, String commentId) {
        // We're always updating the cache, but that often happens in a separate thread.
        // let's make sure all of the updates are done before proceeding to the next
        // test
        await().atMost(5, SECONDS)
                .until(() -> BaseQueryCache.RECENT_BOT_CONTENT.get(nodeId) != null);

        BotComment botComment = BaseQueryCache.RECENT_BOT_CONTENT.get(nodeId);
        Assertions.assertEquals(commentId, botComment.getCommentId(),
                "Cached Bot Comment ID for " + nodeId + " should equal " + commentId + ", was "
                        + botComment.getCommentId());
        return botComment;
    }
}
