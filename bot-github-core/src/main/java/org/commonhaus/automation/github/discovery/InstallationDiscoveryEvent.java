package org.commonhaus.automation.github.discovery;

import org.kohsuke.github.GitHub;

import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public record InstallationDiscoveryEvent(
        long installationId,
        GitHub github,
        DynamicGraphQLClient graphQLClient) {
}
