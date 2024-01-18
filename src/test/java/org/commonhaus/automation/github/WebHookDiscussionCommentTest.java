package org.commonhaus.automation.github;

import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEvent;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkiverse.githubapp.testing.GitHubAppTesting;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class WebHookDiscussionCommentTest {

    @Test
    public void testDiscussionCommentCreated() throws Exception {
        GitHubAppTesting.when()
                .payloadFromClasspath("/github/eventDiscussionCommentCreated.json")
                .event(GHEvent.DISCUSSION_COMMENT)
                .then().github(mocks -> {
                    // TODO: something besides making sure it parses
                    // Mockito.verify(mocks.issue(750705278))
                    // .comment("Hello from my GitHub App");
                });
    }

    @Test
    public void testDiscussionCommentDeleted() throws Exception {
        GitHubAppTesting.when()
                .payloadFromClasspath("/github/eventDiscussionCommentDeleted.json")
                .event(GHEvent.DISCUSSION_COMMENT)
                .then().github(mocks -> {
                    // TODO: something besides making sure it parses
                    // Mockito.verify(mocks.issue(750705278))
                    // .comment("Hello from my GitHub App");
                });
    }

    @Test
    public void testDiscussionCommentEdited() throws Exception {
        GitHubAppTesting.when()
                .payloadFromClasspath("/github/eventDiscussionCommentEdited.json")
                .event(GHEvent.DISCUSSION_COMMENT)
                .then().github(mocks -> {
                    // TODO: something besides making sure it parses
                    // Mockito.verify(mocks.issue(750705278))
                    // .comment("Hello from my GitHub App");
                });
    }
}
