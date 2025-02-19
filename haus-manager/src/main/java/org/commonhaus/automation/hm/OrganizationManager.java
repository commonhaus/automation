package org.commonhaus.automation.hm;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.commonhaus.automation.hm.config.ManagerBotConfig;
import org.commonhaus.automation.hm.config.OrganizationConfig;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkus.logging.Log;

@ApplicationScoped
public class OrganizationManager {

    final FileWatcher fileEvents;
    final ManagerBotConfig mgrBotConfig;
    final AppContextService appContext;

    OrganizationConfig orgConfig;

    public OrganizationManager(AppContextService appContext, FileWatcher fileEvents, ManagerBotConfig mgrBotConfig) {
        this.appContext = appContext;
        this.fileEvents = fileEvents;
        this.mgrBotConfig = mgrBotConfig;
    }

    /**
     * Event handler for repository discovery.
     * Specifically look for (and monitor) organization configuration.
     */
    protected void repositoryDiscovered(@Observes RepositoryDiscoveryEvent repoEvent) {
        DiscoveryAction action = repoEvent.action();
        GHRepository repo = repoEvent.repository();
        String repoFullName = repo.getFullName();
        String orgName = ScopedQueryContext.toOrganizationName(repoFullName);

        if (action.added()) {
            if (orgName.equals(mgrBotConfig.configOrganization()) && repo.getName().equals(mgrBotConfig.mainRepository())) {
                // main repository for configuration
                Log.debugf("orgMgr/repoDiscovered: added main=%s", repoFullName);
                // read org config at startup
                readOrgConfig(repoEvent.installationId(), repoEvent.github(), repo);
                // Register watcher to monitor for org config changes
                fileEvents.watchFile(repoEvent.installationId(), repoFullName, OrganizationConfig.PATH,
                        (fileEvent) -> readOrgConfig(repoEvent.installationId(), fileEvent.github(), fileEvent.repository()));
            }
        } else if (action.removed()) {
            if (orgName.equals(mgrBotConfig.configOrganization()) && repo.getName().equals(mgrBotConfig.mainRepository())) {
                // main repository for configuration
                Log.debugf("orgMgr/repoDiscovered: removed main=%s", repoFullName);
                orgConfig = null;
            }
        }
    }

    /**
     * Read organization configuration from repository.
     * Called by repositoryDiscovered and for fileEvents.
     *
     * @param installationId
     * @param repoFullName
     * @param github
     */
    protected void readOrgConfig(long installationId, GitHub github, GHRepository repository) {
        ScopedQueryContext qc = new ScopedQueryContext(appContext, installationId, repository)
                .addExisting(github);
        OrganizationConfig orgCfg = qc.readYamlSourceFile(qc.getRepository(),
                OrganizationConfig.PATH, OrganizationConfig.class);

        if (orgCfg != null) {
            orgConfig = orgCfg;
            Log.debugf("orgMgr/readOrgConfig: %s", orgConfig);
        }
    }
}
