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
        Optional<?> repoConfig) {

    public boolean added() {
        return action == DiscoveryAction.ADDED;
    }

    public boolean removed() {
        return action == DiscoveryAction.REMOVED;
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> getRepositoryConfig() {
        return (Optional<T>) repoConfig;
    }
}
