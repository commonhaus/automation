package org.commonhaus.automation.github;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEvent;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkiverse.githubapp.testing.GitHubAppTesting;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class WebHookDiscussionTest {

    @Test
    void testDiscussionCreated() throws IOException {
        GitHubAppTesting.when()
                .payloadFromClasspath("/github/eventDiscussionCreated.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    // TODO: something besides making sure it parses
                    // Mockito.verify(mocks.issue(750705278))
                    //         .comment("Hello from my GitHub App");
                });
    }

    @Test
    void testDiscussionLabeled() throws IOException {
        GitHubAppTesting.when()
                .payloadFromClasspath("/github/eventDiscussionLabeled.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    // TODO: something besides making sure it parses
                    // Mockito.verify(mocks.issue(750705278))
                    //         .comment("Hello from my GitHub App");
                });
    }

    @Test
    void testDiscussionUnlabeled() throws IOException {
        GitHubAppTesting.when()
                .payloadFromClasspath("/github/eventDiscussionUnlabeled.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    // TODO: something besides making sure it parses
                    // Mockito.verify(mocks.issue(750705278))
                    //         .comment("Hello from my GitHub App");
                });
    }
}
