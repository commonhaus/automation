package org.commonhaus.automation.hm;

import jakarta.annotation.Nonnull;

import org.commonhaus.automation.github.context.QueryContext;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

public class ScopedQueryContext extends QueryContext {
    private final String ownerName;
    private final String repoFullName;

    private GHRepository repository;

    /**
     * Repository-level event
     *
     * @param contextService
     * @param installationId
     * @param repoFullName
     */
    public ScopedQueryContext(@Nonnull AppContextService contextService, @Nonnull long installationId,
            @Nonnull GHRepository repository) {
        super(contextService, installationId);
        this.repository = repository;
        this.repoFullName = repository.getFullName();
        this.ownerName = toOrganizationName(repoFullName);
    }

    /**
     * Repository-level event
     *
     * @param contextService
     * @param installationId
     * @param repoFullName
     */
    public ScopedQueryContext(@Nonnull AppContextService contextService, @Nonnull long installationId,
            @Nonnull String repoFullName) {
        super(contextService, installationId);
        this.repoFullName = repoFullName;
        this.ownerName = toOrganizationName(repoFullName);
    }

    /**
     * In this path, repository could be null (org-level event)
     *
     * @param contextService
     * @param installationId
     * @param orgName
     * @param repoFullName (maybe)
     */
    public ScopedQueryContext(@Nonnull AppContextService contextService, @Nonnull long installationId, @Nonnull String orgName,
            String repoFullName) {
        super(contextService, installationId);
        this.ownerName = orgName;
        this.repoFullName = repoFullName;
    }

    @Override
    public ScopedQueryContext addExisting(GitHub github) {
        super.addExisting(github);
        return this;
    }

    @Override
    public GHRepository getRepository() {
        if (repository == null && repoFullName != null) {
            repository = getRepository(repoFullName);
        }
        return repository;
    }

    public static String toOrganizationName(String fullName) {
        return QueryContext.toOrganizationName(fullName);
    }
}
