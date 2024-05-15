package org.commonhaus.automation.github.discovery;

import java.util.Optional;

import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

public record RepositoryDiscoveryEvent(
        GitHub github,
        long installationId,
        GHRepository repository,
        Optional<?> repoConfig) {
    public <T> Optional<T> getRepositoryConfig() {
        return (Optional<T>) repoConfig;
    }
}
