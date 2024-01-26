package org.commonhaus.automation.github;

import static io.quarkiverse.githubapp.testing.GitHubAppTesting.given;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEvent;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class NotifyTest {

    // When a discussion is opened, if it in a notice category,
    @Test
    void discussionOpened() throws IOException {
        given().github(mocks -> {
            mocks.configFile(RepositoryAppConfig.NAME).fromClasspath("/cf-automation.yml");
        })
                .when().payloadFromClasspath("/github/eventDiscussionCreated.json")
                .event(GHEvent.DISCUSSION)
                .then().github(mocks -> {
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

}
