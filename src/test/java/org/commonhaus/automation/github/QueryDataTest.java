package org.commonhaus.automation.github;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class QueryDataTest {

    // @Test
    // void testIssueOpened() throws IOException {
    //     GitHubAppTesting.when()
    //             .payloadFromClasspath("/issue-opened.json")
    //             .event(GHEvent.ISSUES)
    //             .then().github(mocks -> {
    //                 Mockito.verify(mocks.issue(750705278))
    //                         .comment("Hello from my GitHub App");
    //             });
    // }
}
