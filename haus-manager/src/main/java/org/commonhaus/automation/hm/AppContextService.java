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
import org.commonhaus.automation.hm.config.ManagerBotConfig;
import org.commonhaus.automation.mail.LogMailer;

import io.quarkiverse.githubapp.GitHubClientProvider;
import io.vertx.mutiny.core.eventbus.EventBus;

@Singleton
public class AppContextService extends BaseContextService {

    // The bot is installed at the organization level with required permissions
    // (organization/team membership, etc.)
    final Map<Long, AppInstallationContext> installationsById = new ConcurrentHashMap<>();
    final Map<String, AppInstallationContext> installationsByScope = new ConcurrentHashMap<>();
    final ManagerBotConfig mgrBotConfig;

    public AppContextService(
            ManagerBotConfig mgrBotConfig,
            BotConfig botConfig,
            GitHubClientProvider gitHubClientProvider,
            EventBus bus,
            LogMailer logMailer) {
        super(botConfig, gitHubClientProvider, bus, logMailer);
        this.mgrBotConfig = mgrBotConfig;
    }

    /**
     * Event handler for repository discovery.
     * Specifically, ensure we have and can find the right app installation for
     * a repository or organization.
     */
    protected void repositoryDiscovered(@Observes RepositoryDiscoveryEvent repoEvent) {
        DiscoveryAction action = repoEvent.action();
        long installationId = repoEvent.installationId();
        String repoFullName = repoEvent.repository().getFullName();
        String orgName = ScopedQueryContext.toOrganizationName(repoFullName);

        AppInstallationContext appInstallation;
        if (action.added()) {
            appInstallation = installationsById.computeIfAbsent(installationId,
                    id -> new AppInstallationContext(id, orgName));
            appInstallation.add(repoFullName);

            // Cross-reference by org name and repo name
            installationsByScope.put(orgName, appInstallation);
            installationsByScope.put(repoFullName, appInstallation);
        } else if (action.removed()) {
            if (action.installation()) {
                installationsById.remove(installationId);
                installationsByScope.entrySet().removeIf(x -> x.getValue().installationId == installationId);
            } else {
                installationsByScope.remove(repoFullName);
            }
        }
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

        public long installationId() {
            return installationId;
        }
    }

    public ScopedQueryContext getDefaultQueryContext() {
        AppInstallationContext appInstallation = installationsByScope.get(mgrBotConfig.configOrganization());
        return new ScopedQueryContext(this, appInstallation);
    }

    public ScopedQueryContext getOrgScopedQueryContext(String teamOrgName) {
        AppInstallationContext appInstallation = installationsByScope.get(teamOrgName);
        return appInstallation == null
                ? null
                : new ScopedQueryContext(this, appInstallation);
    }
}
