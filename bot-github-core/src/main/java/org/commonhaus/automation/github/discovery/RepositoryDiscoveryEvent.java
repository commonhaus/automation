package org.commonhaus.automation.github.discovery;

import java.util.Optional;

import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public record RepositoryDiscoveryEvent(
        DiscoveryAction action,
        GitHub github,
        DynamicGraphQLClient graphQLClient,
        long installationId,
        GHRepository repository,
        Optional<?> repoConfig,
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

    @SuppressWarnings("unchecked")
    public <T> Optional<T> getRepositoryConfig() {
        return (Optional<T>) repoConfig;
    }
}
