package org.commonhaus.automation.github.notice;

import static io.quarkiverse.githubapp.testing.GitHubAppTesting.given;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import jakarta.inject.Inject;

import org.commonhaus.automation.RepositoryConfigFile;
import org.commonhaus.automation.github.test.ContextHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEvent;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class NotifyEmailTest extends ContextHelper {
    @Inject
    MockMailbox mailbox;

    @BeforeEach
    void init() {
        mailbox.clear();
    }

    @Test
    void discussionCreated_NoEmail() throws Exception {
        // When a general, not-interesting, discussion is created,
        // - no rules or actions are triggered
        given()
                .github(mocks -> mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-notice-email.yml"))
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
        verifyNoLabelCache(discussionId);

        setLabels(repositoryId, notice);
        setLabels(discussionId, notice);

        given()
                .github(mocks -> mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-notice-email.yml"))
                .when().payloadFromClasspath("/github/eventDiscussionCreatedAnnouncements.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    // 1 times: repo labels
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() != 0);
    }

    @Test
    void discussionCreatedAnnouncementsNoLabel_NoEmail() throws Exception {
        // When a discussion is created in Announcements
        // - labels are fetched (label rule)
        // - if notice label is NOT present, an email is NOT sent

        String discussionId = "D_kwDOLDuJqs4AXaZM";
        verifyNoLabelCache(discussionId);

        // preset cache to avoid requests
        setLabels(repositoryId, notice);
        setLabels(discussionId, bug);

        given()
                .github(mocks -> mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-notice-email.yml"))
                .when().payloadFromClasspath("/github/eventDiscussionCreatedAnnouncements.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    // 1 times: repo labels
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        await().failFast(() -> mailbox.getTotalMessagesSent() != 0)
                .atMost(5, SECONDS);
    }

    @Test
    void discussionAnnouncementsLabeledNotice_SendEmail() throws Exception {
        // When a the notice label is added to a discussion, send an email

        String discussionId = "D_kwDOLDuJqs4AXNhB";

        // preset cache to avoid requests
        setLabels(repositoryId, notice);
        setLabels(discussionId, bug);

        given()
                .github(mocks -> mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-notice-email.yml"))
                .when().payloadFromClasspath("/github/eventDiscussionLabeled.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() != 0);
    }

    @Test
    void testPrLabeledNotice_SendEmail() throws Exception {
        // If a PR is labeled with notice, send an email

        String prNodeId = "PR_kwDOLDuJqs5mDkwX";

        // preset cache to avoid requests
        setLabels(repositoryId, notice);
        setLabels(prNodeId, bug);

        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-notice-email.yml");
                })
                .when().payloadFromClasspath("/github/eventPullRequestLabeled.json")
                .event(GHEvent.PULL_REQUEST)
                .then().github(mocks -> {
                    verifyNoMoreInteractions(mocks.installationGraphQLClient(installationId));
                    verifyNoMoreInteractions(mocks.ghObjects());
                });

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() != 0);
    }
}
