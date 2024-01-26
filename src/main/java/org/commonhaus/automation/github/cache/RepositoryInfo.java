package org.commonhaus.automation.github.cache;

import org.kohsuke.github.GHRepository;

public class RepositoryInfo {
    public final Long installationId;
    public final String fullName;
    public final String owner;
    public final String name;

    public RepositoryInfo(Long installationId, GHRepository repo) {
        this.installationId = installationId;
        this.fullName = repo.getFullName();
        this.owner = repo.getOwnerName();
        this.name = repo.getName();
    }
}
