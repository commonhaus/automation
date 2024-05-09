package org.commonhaus.automation.github.discovery;

import java.util.Optional;

import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

public class RepositoryDiscoveryEvent {
    private final long installationId;
    private final GHRepository ghRepository;

    private final GitHub github;

    private final Optional<?> repoConfig;

    public RepositoryDiscoveryEvent(GitHub github, long installationId, GHRepository ghRepository,
            Optional<?> repoConfig) {
        this.installationId = installationId;
        this.ghRepository = ghRepository;
        this.repoConfig = repoConfig;
        this.github = github;
    }

    public long getInstallationId() {
        return installationId;
    }

    public GHRepository getRepository() {
        return ghRepository;
    }

    public GitHub getGitHub() {
        return github;
    }

    public <T> Optional<T> getRepositoryConfig() {
        return (Optional<T>) repoConfig;
    }
}
