package org.commonhaus.automation.github.discovery;

import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.GitHubEvent;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public record ConnectionEvent(
        GitHubEvent event,
        GitHub github,
        DynamicGraphQLClient graphQLClient) {

}
