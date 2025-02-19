package org.commonhaus.automation.hm;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import org.commonhaus.automation.config.BotConfig;
import org.commonhaus.automation.github.context.BaseContextService;
import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.kohsuke.github.GHRepository;

import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkiverse.githubapp.GitHubConfigFileProvider;
import io.vertx.mutiny.core.eventbus.EventBus;

@Singleton
public class AppContextService extends BaseContextService {

    // The bot is installed at the organization level with required permissions
    // (organization/team membership, etc.)
    final Map<Long, AppInstallationContext> installations = new ConcurrentHashMap<>();

    public AppContextService(
            BotConfig data,
            GitHubClientProvider gitHubClientProvider,
            GitHubConfigFileProvider configProvider,
            EventBus bus) {
        super(data, gitHubClientProvider, configProvider, bus);
    }

    /**
     * Event handler for repository discovery.
     */
    @Override
    protected void repositoryDiscovered(@Observes RepositoryDiscoveryEvent repoEvent) {
        super.repositoryDiscovered(repoEvent);

        DiscoveryAction action = repoEvent.action();
        long installationId = repoEvent.installationId();
        GHRepository repo = repoEvent.repository();
        String repoFullName = repo.getFullName();
        String orgName = ScopedQueryContext.toOrganizationName(repoFullName);

        if (action.added()) {
            installations.computeIfAbsent(installationId,
                    k -> new AppInstallationContext(installationId, orgName))
                    .add(repoFullName);

        } else if (action.removed()) {
            if (action.installation()) {
                // entire installation removed
                AppInstallationContext ic = installations.remove(installationId);
            } else {
                // single repo
                AppInstallationContext ic = installations.get(installationId);
            }
        }
    }

    @Override
    public Class<?> getConfigType() {
        return null;
    }

    @Override
    public String getConfigFileName() {
        return null;
    }

    static class AppInstallationContext {
        final long installationId;
        final String orgName;
        private final Set<String> repositories = new HashSet<>();

        public AppInstallationContext(long installationId, String orgName) {
            this.installationId = installationId;
            this.orgName = orgName;
        }

        public void add(String repoName) {
            repositories.add(repoName);
        }
    }
}
