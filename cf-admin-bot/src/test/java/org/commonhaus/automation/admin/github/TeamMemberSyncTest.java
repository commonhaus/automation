package org.commonhaus.automation.admin.github;

import static io.quarkiverse.githubapp.testing.GitHubAppTesting.given;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import jakarta.inject.Inject;

import org.commonhaus.automation.admin.config.AdminConfigFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHEvent;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class TeamMemberSyncTest extends ContextHelper {

    @Inject
    MockMailbox mailbox;

    @Inject
    AppContextService ctx;

    @BeforeEach
    void init() {
        mailbox.clear();
    }

    @Test
    void testDiscoveryWhenInstallationAdded() throws Exception {
        given()
                .github(mocks -> {
                    mocks.configFile(AdminConfigFile.NAME).fromClasspath("/cf-admin.yml");

                    GitHub gh = setupMockTeam(mocks);
                    setupMockRepository(mocks, gh, ctx, ctx.getDataStore());

                    GHRepository foundationRepo = setupMockRepository(mocks, gh, ctx, "commonhaus/foundation");

                    GHContent content = mock(GHContent.class);
                    when(content.read()).thenReturn(Files.newInputStream(Path.of("src/test/resources/CONTACTS.yaml")));

                    when(foundationRepo.getFileContent("CONTACTS.yaml"))
                            .thenReturn(content);

                })
                .when().payloadFromClasspath("/github/eventInstallationAdded.json")
                .event(GHEvent.INSTALLATION_REPOSITORIES)
                .then().github(mocks -> {

                });

        await().atMost(15, SECONDS).until(() -> mailbox.getTotalMessagesSent() >= 3);
        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).hasSize(0);
        assertThat(mailbox.getMailsSentTo("repo-errors@example.com")).hasSize(0);
        List<Mail> mailList = mailbox.getMailsSentTo("dry-run@example.com");
        assertThat(mailList).hasSize(3);

        for (Mail m : mailList) {
            String subject = m.getSubject();
            String body = m.getText();
            String finalGroup = body.replaceAll("(?s).*?(Final:.*)", "$1");
            System.out.println("final group: " + finalGroup);

            // see test CONTACTS.yaml for group members
            // see cf-admin.yml for sync parameters
            if (subject.contains("commonhaus-test/cf-council") || subject.contains("commonhaus-test/cf-voting")) {
                // cf-council and cf-voting should be the same
                assertTeamLogins(finalGroup,
                        List.of("user1 ()", "user2 ()", "user3 ()", "user9 ()"));
            }
            if (subject.contains("commonhaus-test/team-quorum-default")) {
                // team quorum has different membership + other preserved value
                assertTeamLogins(finalGroup,
                        List.of("user4 ()", "user5 ()", "user6 ()", "user7 ()", "user8 ()", "user9 ()", "user12 ()"));
            }
        }
    }

    void assertTeamLogins(String text, List<String> logins) {
        for (int i = 1; i < 15; i++) {
            String login = "user" + i + " ()";
            if (logins.contains(login)) {
                assertThat(text).contains(login);
            } else {
                assertThat(text).doesNotContain(login);
            }
        }
    }

    @Test
    void testDiscoveryWhenInstallationCreated() throws Exception {
        given()
                .github(mocks -> {
                    GitHub gh = setupMockTeam(mocks);
                    setupMockRepository(mocks, gh, ctx, ctx.getDataStore());
                    GHRepository foundationRepo = setupMockRepository(mocks, gh, ctx, "commonhaus/foundation");

                    mocks.configFile(AdminConfigFile.NAME).fromClasspath("/cf-admin.yml");

                    GHContent content = mock(GHContent.class);
                    when(content.read()).thenReturn(Files.newInputStream(Path.of("src/test/resources/CONTACTS.yaml")));

                    when(foundationRepo.getFileContent("CONTACTS.yaml")).thenReturn(content);
                })
                .when().payloadFromClasspath("/github/eventInstallationCreated.json")
                .event(GHEvent.INSTALLATION)
                .then().github(mocks -> {

                });

        await().atMost(15, SECONDS).until(() -> mailbox.getTotalMessagesSent() >= 3);
        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).hasSize(0);
        assertThat(mailbox.getMailsSentTo("repo-errors@example.com")).hasSize(0);
        assertThat(mailbox.getMailsSentTo("dry-run@example.com")).hasSize(3);
    }

    @Test
    void testQueryFailed() throws Exception {
        given()
                .github(mocks -> {
                    GitHub gh = setupMockTeam(mocks);
                    GHRepository foundationRepo = setupMockRepository(mocks, gh, ctx, "commonhaus/foundation");

                    mocks.configFile(AdminConfigFile.NAME).fromClasspath("/cf-admin.yml");

                    when(foundationRepo.getFileContent("CONTACTS.yaml"))
                            .thenThrow(new IOException("Test exception"));
                })
                .when().payloadFromClasspath("/github/eventInstallationAdded.json")
                .event(GHEvent.INSTALLATION_REPOSITORIES)
                .then().github(mocks -> {

                });

        await().atMost(15, SECONDS).until(() -> mailbox.getTotalMessagesSent() != 0);
        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).hasSize(0);
        assertThat(mailbox.getMailsSentTo("repo-errors@example.com")).hasSize(1);
        assertThat(mailbox.getMailsSentTo("dry-run@example.com")).hasSize(0);
    }
}
