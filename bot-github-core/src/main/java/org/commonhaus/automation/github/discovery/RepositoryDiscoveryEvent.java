package org.commonhaus.automation.github.discovery;

import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public record RepositoryDiscoveryEvent(
        DiscoveryAction action,
        GitHub github,
        DynamicGraphQLClient graphQLClient,
        long installationId,
        GHRepository repository,
        boolean bootstrap) {

    public boolean added() {
        return action.added();
    }

    public boolean removed() {
        return action.removed();
    }

    public boolean installation() {
        return action.installation();
    }
}
