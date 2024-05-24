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

import jakarta.inject.Inject;

import org.commonhaus.automation.admin.RepositoryConfigFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHEvent;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class TeamMemberSyncTest extends ContextHelper {

    @Inject
    MockMailbox mailbox;

    @BeforeEach
    void init() {
        mailbox.clear();
    }

    @Test
    void testDiscoveryWhenInstallationAdded() throws Exception {
        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-admin.yml");
                    setupMockTeam(mocks);

                    GHContent content = mock(GHContent.class);
                    when(content.read()).thenReturn(Files.newInputStream(Path.of("src/test/resources/CONTACTS.yaml")));

                    when(mocks.repository("commonhaus/foundation").getFileContent("CONTACTS.yaml"))
                            .thenReturn(content);
                })
                .when().payloadFromClasspath("/github/eventInstallationAdded.json")
                .event(GHEvent.INSTALLATION_REPOSITORIES)
                .then().github(mocks -> {

                });

        await().atMost(15, SECONDS).until(() -> mailbox.getTotalMessagesSent() >= 3);
        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).hasSize(0);
        assertThat(mailbox.getMailsSentTo("repo-errors@example.com")).hasSize(0);
        assertThat(mailbox.getMailsSentTo("dry-run@example.com")).hasSize(3);
    }

    @Test
    void testDiscoveryWhenInstallationCreated() throws Exception {
        given()
                .github(mocks -> {
                    mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-admin.yml");
                    setupMockTeam(mocks);

                    GHContent content = mock(GHContent.class);
                    when(content.read()).thenReturn(Files.newInputStream(Path.of("src/test/resources/CONTACTS.yaml")));

                    when(mocks.repository("commonhaus/foundation").getFileContent("CONTACTS.yaml"))
                            .thenReturn(content);
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
                    mocks.configFile(RepositoryConfigFile.NAME).fromClasspath("/cf-admin.yml");
                    setupMockTeam(mocks);

                    GHContent content = mock(GHContent.class);
                    when(content.read()).thenReturn(Files.newInputStream(Path.of("src/test/resources/CONTACTS.yaml")));

                    when(mocks.repository("commonhaus/foundation").getFileContent("CONTACTS.yaml"))
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
