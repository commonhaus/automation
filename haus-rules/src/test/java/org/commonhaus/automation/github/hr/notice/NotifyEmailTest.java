package org.commonhaus.automation.github.hr.notice;

import static io.quarkiverse.githubapp.testing.GitHubAppTesting.given;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.commonhaus.automation.github.hr.HausRulesTestBase;
import org.commonhaus.automation.github.hr.config.HausRulesConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEvent;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class NotifyEmailTest extends HausRulesTestBase {

    private static final String DISCUSSION_ID = "D_kwDOLDuJqs4AXaZM";

    @BeforeEach
    void init() {
        mailbox.clear();
    }

    @Test
    void discussionCreated_NoEmail() throws Exception {
        // When a general, not-interesting, discussion is created,
        // - no rules or actions are triggered
        given()
                .github(mocks -> mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-notice-email.yml"))
                .when().payloadFromClasspath("/github/eventDiscussionCreated.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        await().failFast(() -> mailbox.getTotalMessagesSent() != 0)
                .atMost(5, SECONDS);
    }

    @Test
    void discussionCreatedAnnouncements_SendEmail() throws Exception {
        // When a discussion is created in Announcements
        // - labels are fetched (label rule)
        // - if notice label is present, an email is sent

        String discussionId = "D_kwDOLDuJqs4AXaZM";
        verifyNoLabels(discussionId);

        // Set up labels in cache
        setLabels(repositoryId, NOTICE);
        setLabels(DISCUSSION_ID, NOTICE);

        given()
                .github(mocks -> mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-notice-email.yml"))
                .when().payloadFromClasspath("/github/eventDiscussionCreatedAnnouncements.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    // 1 times: repo labels
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() != 0);
        assertThat(mailbox.getMailsSentTo("test@commonhaus.org")).hasSize(1);
    }

    @Test
    void discussionCreatedAnnouncementsNoLabel_NoEmail() throws Exception {
        // When a discussion is created in Announcements
        // - labels are fetched (label rule)
        // - if notice label is NOT present, an email is NOT sent

        String discussionId = "D_kwDOLDuJqs4AXaZM";
        verifyNoLabels(discussionId);

        // preset cache to avoid requests
        setLabels(repositoryId, NOTICE);
        setLabels(discussionId, BUG);

        given()
                .github(mocks -> mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-notice-email.yml"))
                .when().payloadFromClasspath("/github/eventDiscussionCreatedAnnouncements.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    // 1 times: repo labels
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        await().atMost(5, SECONDS).failFast(() -> mailbox.getTotalMessagesSent() != 0);
        assertThat(mailbox.getMailsSentTo("test@commonhaus.org")).hasSize(0);
    }

    @Test
    void issueCommentVoteResultBody_NoEmail() throws Exception {
        String issueId = "PR_kwDOLDuJqs5mlMVl";
        verifyNoLabels(issueId);

        // preset cache to avoid requests
        setLabels(repositoryId, NOTICE);
        setLabels(issueId, NOTICE, VOTE_OPEN);

        given()
                .github(mocks -> mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-notice-email.yml"))
                .when().payloadFromClasspath("/github/eventIssueCommentCreatedBotVoteComment.json")
                .event(GHEvent.ISSUE_COMMENT)
                .then().github(mocks -> {
                    // 1 times: repo labels
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        await().atMost(5, SECONDS).failFast(() -> mailbox.getTotalMessagesSent() != 0);
        assertThat(mailbox.getMailsSentTo("test@commonhaus.org")).hasSize(0);
    }

    @Test
    void discussionAnnouncementsLabeledNotice_SendEmail() throws Exception {
        // When a the notice label is added to a discussion, send an email

        String discussionId = "D_kwDOLDuJqs4AXNhB";

        // preset cache to avoid requests
        setLabels(repositoryId, NOTICE);
        setLabels(discussionId, BUG);

        given()
                .github(mocks -> {
                    mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-notice-email.yml");
                })
                .when().payloadFromClasspath("/github/eventDiscussionLabeled.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() != 0);
        assertThat(mailbox.getMailsSentTo("test@commonhaus.org")).hasSize(1);
    }

    @Test
    void discussionCommentCreatedLabeled_SendEmail() throws Exception {
        // Discussion comment on labeled discussion
        String discussionId = "D_kwDOLDuJqs4AXOh5";
        verifyNoLabels(discussionId);

        setLabels(repositoryId, NOTICE);
        setLabels(discussionId, NOTICE);

        given()
                .github(mocks -> mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-notice-email.yml"))
                .when().payloadFromClasspath("/github/eventDiscussionCommentCreated.json")
                .event(GHEvent.DISCUSSION_COMMENT)
                .then().github(mocks -> {
                    // 1 times: repo labels
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() != 0);
        assertThat(mailbox.getMailsSentTo("test@commonhaus.org")).hasSize(1);
    }

    @Test
    void testPrLabeledNotice_SendEmail() throws Exception {
        // If a PR is labeled with notice, send an email

        String prNodeId = "PR_kwDOLDuJqs5mDkwX";

        // preset cache to avoid requests
        setLabels(repositoryId, NOTICE);
        setLabels(prNodeId, BUG);

        given()
                .github(mocks -> {
                    mocks.configFile(HausRulesConfig.NAME).fromClasspath("/cf-notice-email.yml");
                })
                .when().payloadFromClasspath("/github/eventPullRequestLabeled.json")
                .event(GHEvent.PULL_REQUEST)
                .then().github(mocks -> {
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                });

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() != 0);
        assertThat(mailbox.getMailsSentTo("test@commonhaus.org")).hasSize(1);
    }
}
