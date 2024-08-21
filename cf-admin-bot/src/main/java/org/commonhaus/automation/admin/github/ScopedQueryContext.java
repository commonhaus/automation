package org.commonhaus.automation.admin.github;

import jakarta.annotation.Nonnull;

import org.commonhaus.automation.github.context.ActionType;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.github.context.QueryContext;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public class ScopedQueryContext extends QueryContext {
    private final String ownerName;
    private final String repoFullName;

    private GHRepository repository;
    private MonitoredRepo repoConfig;

    /**
     * In this path, repository could be null (org-level event)
     *
     * @param contextService
     * @param installationId
     * @param orgName
     * @param repoFullName
     */
    public ScopedQueryContext(@Nonnull AppContextService contextService, @Nonnull long installationId, @Nonnull String orgName,
            String repoFullName) {
        super(contextService, installationId);
        this.ownerName = orgName;
        this.repoFullName = repoFullName;
    }

    public ScopedQueryContext(@Nonnull AppContextService contextService, @Nonnull long installationId,
            @Nonnull GHRepository repo) {
        super(contextService, installationId);
        this.repoFullName = repo.getFullName();
        this.ownerName = QueryContext.toOrganizationName(repoFullName);
        this.repository = repo;
    }

    /**
     * In this path, repository could be null (org-level event)
     *
     * @param contextService
     * @param installationId
     * @param org GHOrganization
     * @param repo GHRepository
     */
    public ScopedQueryContext(@Nonnull AppContextService contextService, @Nonnull long installationId,
            @Nonnull GHOrganization org, GHRepository repo) {
        super(contextService, installationId);
        this.ownerName = org.getLogin();
        this.repoFullName = repo == null ? null : repo.getFullName();
        this.repository = repo;
    }

    @Override
    public String getRepositoryId() {
        GHRepository repo = getRepository();
        return repo == null ? null : getRepository().getNodeId();
    }

    @Override
    public GHRepository getRepository() {
        if (repository == null && repoFullName != null) {
            repository = getRepository(repoFullName);
        }
        return repository;
    }

    @Override
    public EventType getEventType() {
        return EventType.bot;
    }

    @Override
    public ActionType getActionType() {
        return ActionType.bot;
    }

    public ScopedQueryContext addExisting(GitHub github) {
        super.addExisting(github);
        return this;
    }

    public ScopedQueryContext addExisting(DynamicGraphQLClient graphQLClient) {
        super.addExisting(graphQLClient);
        return this;
    }

    public ScopedQueryContext addExisting(MonitoredRepo repoCfg) {
        this.repoConfig = repoCfg;
        return this;
    }

    @Override
    public GHOrganization getOrganization() {
        return getOrganization(ownerName);
    }

    @Override
    public String[] getErrorAddresses() {
        String[] errorAddresses = repoConfig == null ? null : repoConfig.errorEmailAddress();
        return errorAddresses == null
                ? super.getErrorAddresses()
                : errorAddresses;
    }

    public String[] dryRunEmailAddress() {
        return repoConfig == null ? null : repoConfig.dryRunEmailAddress();
    }
}
