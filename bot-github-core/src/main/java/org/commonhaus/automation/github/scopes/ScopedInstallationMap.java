package org.commonhaus.automation.github.scopes;

import static org.commonhaus.automation.github.context.GitHubQueryContext.toOrganizationName;
import static org.commonhaus.automation.github.context.GitHubQueryContext.toRelativeName;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import org.commonhaus.automation.ContextService;
import org.commonhaus.automation.github.discovery.ConnectionEvent;
import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent.RdePriority;

import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkus.logging.Log;

@Singleton
public class ScopedInstallationMap {

    final Map<Long, AppInstallationState> installationsById = new ConcurrentHashMap<>();
    final Map<String, AppInstallationState> installationsByScope = new ConcurrentHashMap<>();

    public Optional<Long> getInstallationId(String orgOrFullName) {
        String orgName = toOrganizationName(orgOrFullName);
        return Optional.ofNullable(installationsByScope.get(orgName))
                .map(x -> Optional.of(x.installationId())).orElse(Optional.empty());
    }

    public Optional<String> getOrganization(long installationId) {
        return Optional.ofNullable(installationsById.get(installationId))
                .map(x -> Optional.of(x.orgName)).orElse(Optional.empty());
    }

    public ScopedQueryContext getOrgScopedQueryContext(ContextService ctx, String orgOrFullName) {
        String orgName = toOrganizationName(orgOrFullName);
        String repoName = orgName.contains("/") ? toRelativeName(orgName, orgOrFullName) : null;

        AppInstallationState appInstallation = installationsByScope.get(orgName);
        return appInstallation == null
                ? null
                : new ScopedQueryContext(ctx, appInstallation, repoName);
    }

    protected void updateInstallationMapping(
            @Observes @Priority(value = RdePriority.CONNECTED) ConnectionEvent connectEvent) {
        GitHubEvent event = connectEvent.event();
        Optional<String> optRepoFullName = event.getRepository();
        if (optRepoFullName.isPresent()) {
            updateInstallationMap(event.getInstallationId(), optRepoFullName.get());
        }
    }

    /**
     * Event handler for repository discovery.
     * Specifically, ensure we have and can find the right app installation for
     * a repository or organization.
     */
    protected void repositoryDiscovered(
            @Observes @Priority(value = RdePriority.CORE_DISCOVERY) RepositoryDiscoveryEvent repoEvent) {
        DiscoveryAction action = repoEvent.action();
        long installationId = repoEvent.installationId();
        String repoFullName = repoEvent.repository().getFullName();

        if (action.added()) {
            Log.debugf("ADDED scopedInstallation %s (%s)", action, repoFullName, installationId);
            // Cross-reference by org name and repo name
            updateInstallationMap(installationId, repoFullName);
        } else if (action.removed()) {
            if (action.installation()) {
                installationsById.remove(installationId);
                installationsByScope.entrySet().removeIf(x -> x.getValue().installationId == installationId);
            } else {
                installationsByScope.remove(repoFullName);
            }
        }
    }

    private void updateInstallationMap(long installationId, String repoFullName) {
        String orgName = toOrganizationName(repoFullName);

        AppInstallationState appInstallation = new AppInstallationState(installationId, orgName);
        installationsById.put(installationId, appInstallation);
        installationsByScope.put(orgName, appInstallation);

        if (repoFullName.contains("/")) {
            // Also store by full repo name
            installationsByScope.put(repoFullName, appInstallation);
        }
    }

    static record AppInstallationState(
            long installationId,
            String orgName) {
    }
}
