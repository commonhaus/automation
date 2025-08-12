package org.commonhaus.automation.github.scopes;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import jakarta.annotation.Nonnull;

import org.commonhaus.automation.ContextService;
import org.commonhaus.automation.config.EmailNotification;
import org.commonhaus.automation.github.context.ActionType;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.github.context.GitHubQueryContext;
import org.commonhaus.automation.github.scopes.ScopedInstallationMap.AppInstallationState;
import org.commonhaus.automation.github.watchers.MembershipWatcher.MembershipUpdate;
import org.commonhaus.automation.github.watchers.MembershipWatcher.MembershipUpdateType;
import org.commonhaus.automation.github.watchers.MembershipWatcher.RepositoryEvent;
import org.commonhaus.automation.github.watchers.MembershipWatcher.TeamEvent;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkus.logging.Log;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public class ScopedQueryContext extends GitHubQueryContext {
    private final String ownerName;
    private final String repoFullName;

    private GHRepository repository;

    private ScopedQueryContext(
            @Nonnull ContextService contextService,
            @Nonnull long installationId,
            GHRepository repository,
            String repoFullName,
            String ownerName) {
        super(contextService, installationId);
        this.ownerName = ownerName;
        this.repoFullName = (repoFullName != null && repoFullName.contains("/")) ? repoFullName : null;
        this.repository = (repository == null && this.repoFullName != null) ? getRepository(this.repoFullName) : repository;
    }

    public ScopedQueryContext(
            @Nonnull ContextService contextService,
            @Nonnull long installationId,
            @Nonnull GHRepository repository) {
        this(contextService, installationId, repository,
                repository.getFullName(),
                toOrganizationName(repository.getFullName()));
    }

    public ScopedQueryContext(
            @Nonnull ContextService contextService,
            @Nonnull long installationId,
            @Nonnull String repoFullName) {
        this(contextService, installationId, null,
                repoFullName,
                toOrganizationName(repoFullName));
    }

    public ScopedQueryContext(
            @Nonnull ContextService contextService,
            @Nonnull long installationId,
            @Nonnull String orgName,
            String repoFullName) {
        this(contextService, installationId, null, repoFullName, orgName);
    }

    public ScopedQueryContext(
            @Nonnull ContextService contextService,
            @Nonnull AppInstallationState installState,
            String repoFullName) {
        this(contextService,
                installState.installationId(),
                null,
                repoFullName,
                installState.orgName());
    }

    @Override
    public ScopedQueryContext withExisting(GitHub github) {
        super.withExisting(github);
        return this;
    }

    @Override
    public ScopedQueryContext withExisting(DynamicGraphQLClient dql) {
        super.withExisting(dql);
        return this;
    }

    /**
     * @return a ScopedQueryContext suitable to modify the membership of the target
     *         team.
     */
    public ScopedQueryContext forOrganization(String fullName, boolean isDryRun) {
        String orgName = ScopedQueryContext.toOrganizationName(fullName);

        // If same organization, just use the original context
        if (isSameOrganization(orgName)) {
            return this;
        }

        // Otherwise, create a new context for the target organization
        ScopedQueryContext newContext = ctx.getOrgScopedQueryContext(orgName);
        // Fall back to original context in dry run mode
        if (newContext == null) {
            Log.warnf("[%s] No installation for %s (dry run: %b)", getLogId(), orgName, isDryRun);
            return isDryRun ? this : null;
        }
        return newContext;
    }

    /**
     * @return a ScopedQueryContext that can read public content from the target
     */
    public ScopedQueryContext forPublicContent(String fullName) {
        String orgName = ScopedQueryContext.toOrganizationName(fullName);

        // If same organization, just use the original context
        if (isSameOrganization(orgName)) {
            return this;
        }

        // Otherwise, create a new context for the target organization
        ScopedQueryContext newContext = ctx.getOrgScopedQueryContext(orgName);
        return newContext == null ? this : newContext;
    }

    @Override
    public String getRepositoryId() {
        GHRepository repo = getRepository();
        return repo == null ? null : repo.getNodeId();
    }

    @Override
    public GHRepository getRepository() {
        if (repository == null && repoFullName != null) {
            repository = getRepository(repoFullName);
        }
        return repository;
    }

    @Override
    public GHOrganization getOrganization() {
        return getOrganization(ownerName);
    }

    public boolean isSameOrganization(String orgName) {
        return ownerName.equals(orgName);
    }

    public static ScopedQueryContext forUpdate(ContextService ctx, MembershipUpdate update) {
        if (update.type() == MembershipUpdateType.COLLABORATOR) {
            RepositoryEvent event = update.repositoryEvent();
            return new ScopedQueryContext(ctx, event.installationId(), event.repository())
                    .withExisting(event.github());
        } else {
            TeamEvent event = update.teamEvent();
            String orgName = event.organization().getLogin();
            return new ScopedQueryContext(ctx, event.installationId(), orgName, null)
                    .withExisting(event.github());
        }
    }

    public String[] getErrorAddresses(EmailNotification notifications) {
        Set<String> addresses = new HashSet<>();
        if (notifications != null) {
            Collections.addAll(addresses, notifications.errors());
        }
        Collections.addAll(addresses, getErrorAddresses());
        return addresses.toArray(new String[0]);
    }

    @Override
    public EventType getEventType() {
        return EventType.bot;
    }

    @Override
    public ActionType getActionType() {
        return ActionType.bot;
    }
}
