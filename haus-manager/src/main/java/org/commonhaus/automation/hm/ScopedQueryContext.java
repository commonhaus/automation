package org.commonhaus.automation.hm;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import jakarta.annotation.Nonnull;

import org.commonhaus.automation.config.EmailNotification;
import org.commonhaus.automation.github.context.QueryContext;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdate;
import org.commonhaus.automation.github.watchers.MembershipWatcher.MembershipUpdate;
import org.commonhaus.automation.github.watchers.MembershipWatcher.MembershipUpdateType;
import org.commonhaus.automation.github.watchers.MembershipWatcher.RepositoryEvent;
import org.commonhaus.automation.github.watchers.MembershipWatcher.TeamEvent;
import org.commonhaus.automation.hm.AppContextService.AppInstallationContext;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkus.logging.Log;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public class ScopedQueryContext extends QueryContext {
    private final String ownerName;
    private final String repoFullName;

    private GHRepository repository;
    private AppContextService appCtx;

    /**
     * Repository-level event
     *
     * @param contextService
     * @param installationId
     * @param repoFullName
     */
    private ScopedQueryContext(
            @Nonnull AppContextService contextService,
            @Nonnull long installationId,
            GHRepository repository,
            String repoFullName,
            String ownerName) {
        super(contextService, installationId);
        this.appCtx = contextService;
        this.repository = repository;
        this.repoFullName = repoFullName;
        this.ownerName = ownerName;
    }

    /**
     * Repository-level event
     *
     * @param contextService
     * @param installationId
     * @param repoFullName
     */
    public ScopedQueryContext(@Nonnull AppContextService contextService, @Nonnull long installationId,
            @Nonnull GHRepository repository) {
        this(contextService, installationId, repository,
                repository.getFullName(),
                toOrganizationName(repository.getFullName()));
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
        this(contextService, installationId, null,
                repoFullName, toOrganizationName(repoFullName));
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
        this(contextService, installationId, null,
                repoFullName, orgName);
    }

    /**
     * Used for discovery and scheduled events.
     * The AppInstallationContext is used to associate an app installation
     * with the associated organization it is installed in.
     *
     * There will not be a known repository on this path, which is used primarily
     * for org-level events and team synchronization.
     *
     * @param contextService
     * @param appInstallation
     */
    public ScopedQueryContext(@Nonnull AppContextService contextService, @Nonnull AppInstallationContext appInstallation) {
        this(contextService, appInstallation.installationId, null,
                null, appInstallation.orgName);
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
        ScopedQueryContext newContext = appCtx.getOrgScopedQueryContext(orgName);
        // Fall back to original context in dry run mode
        if (newContext == null) {
            Log.warnf("[%s] No installation for %s (dry run: %b)", getLogId(), orgName, isDryRun);
            return isDryRun ? this : null;
        }
        return newContext;
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

    @Override
    public GHRepository getRepository() {
        if (repository == null && repoFullName != null) {
            repository = getRepository(repoFullName);
        }
        return repository;
    }

    public boolean isSameOrganization(String orgName) {
        return ownerName.equals(orgName);
    }

    public static ScopedQueryContext forUpdate(AppContextService ctx, MembershipUpdate update) {
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

    public static ScopedQueryContext forUpdate(AppContextService ctx, FileUpdate update) {
        return new ScopedQueryContext(ctx, update.installationId(), update.repository())
                .withExisting(update.github());
    }

    public String[] getErrorAddresses(EmailNotification notifications) {
        Set<String> addresses = new HashSet<>();
        if (notifications != null) {
            Collections.addAll(addresses, notifications.errors());
        }
        Collections.addAll(addresses, getErrorAddresses());
        return addresses.toArray(new String[0]);
    }
}
