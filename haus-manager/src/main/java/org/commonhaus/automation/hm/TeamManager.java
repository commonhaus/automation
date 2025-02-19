package org.commonhaus.automation.hm;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.commonhaus.automation.hm.config.ManagerBotConfig;
import org.commonhaus.automation.hm.config.ProjectConfig;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkus.logging.Log;

@ApplicationScoped
public class TeamManager {

    final FileWatcher fileEvents;
    final ManagerBotConfig mgrBotConfig;
    final AppContextService appContext;

    public TeamManager(AppContextService appContext, FileWatcher fileEvents, ManagerBotConfig mgrBotConfig) {
        this.appContext = appContext;
        this.fileEvents = fileEvents;
        this.mgrBotConfig = mgrBotConfig;
    }

    /**
     * Event handler for repository discovery.
     */
    protected void repositoryDiscovered(@Observes RepositoryDiscoveryEvent repoEvent) {
        DiscoveryAction action = repoEvent.action();
        GHRepository repo = repoEvent.repository();
        String repoFullName = repo.getFullName();
        String orgName = ScopedQueryContext.toOrganizationName(repoFullName);
        if (action.added()) {
            if (orgName.equals(mgrBotConfig.configOrganization())) {
                // main repository for configuration
                Log.debugf("projMgr/repoDiscovered: added main=%s", repoFullName);
                // read project config at startup
                readProjectConfig(repoEvent.installationId(), repoEvent.github(), repo);
                // Register watcher to monitor for org config changes
                fileEvents.watchFile(repoEvent.installationId(), repoFullName, ProjectConfig.PATH,
                        (fileEvent) -> readProjectConfig(repoEvent.installationId(), fileEvent.github(),
                                fileEvent.repository()));
            }
        } else if (action.removed()) {

        }
    }

    /**
     * Read project configuration from repository.
     * Called by repositoryDiscovered and for fileEvents.
     *
     * @param installationId
     * @param repoFullName
     * @param github
     */
    protected void readProjectConfig(long installationId, GitHub github, GHRepository repo) {
        ScopedQueryContext qc = new ScopedQueryContext(appContext, installationId, repo);

        qc.addExisting(github);
        ProjectConfig projectConfig = qc.readYamlSourceFile(qc.getRepository(),
                ProjectConfig.PATH, ProjectConfig.class);

        if (projectConfig != null) {
            Log.debugf("projMgr/readProjectConfig: %s", projectConfig);
        }
    }

}
