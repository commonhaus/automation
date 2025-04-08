package org.commonhaus.automation.hk;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent.RdePriority;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.github.watchers.FileWatcher;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdate;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdateType;
import org.commonhaus.automation.hk.config.AdminBotConfig;
import org.commonhaus.automation.hk.config.HausKeeperConfig;
import org.commonhaus.automation.hk.github.AppContextService;
import org.commonhaus.automation.queue.PeriodicUpdateQueue;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkus.logging.Log;

@ApplicationScoped
public class UserManager {
    static final String ME = "userManager";

    @Inject
    protected AppContextService ctx;

    @Inject
    protected AdminBotConfig adminData;

    @Inject
    protected FileWatcher fileEvents;

    @Inject
    protected PeriodicUpdateQueue periodicSync;

    @Inject
    ActiveHausKeeperConfig hkConfig;

    /**
     * Event handler for repository discovery.
     * Specifically look for (and monitor) HausKeeper configuration.
     */
    protected void repositoryDiscovered(
            @Observes @Priority(value = RdePriority.APP_DISCOVERY) RepositoryDiscoveryEvent repoEvent) {
        DiscoveryAction action = repoEvent.action();
        long installationId = repoEvent.installationId();
        GHRepository repo = repoEvent.repository();
        String repoFullName = repo.getFullName();

        if (action.repository()
                && repoFullName.equals(adminData.home().datastore())) {
            // Read config from the datastore repository. Immediately
            Log.debugf("[%s] repoDiscovered: %s main=%s", ME, action.name(), repoFullName);
            if (action.added()) {
                // main repository for configuration
                ScopedQueryContext qc = new ScopedQueryContext(ctx, installationId, repo)
                        .withExisting(repoEvent.github());

                processConfigUpdate(qc);

                fileEvents.watchFile(ME,
                        installationId, repoFullName, HausKeeperConfig.PATH,
                        (fileUpdate) -> processFileUpdate(fileUpdate));
            }
        }
    }

    /**
     * Read organization configuration from repository.
     * Called by for file update events.
     */
    protected void processFileUpdate(FileUpdate fileUpdate) {
        GitHub github = fileUpdate.github();
        GHRepository repo = fileUpdate.repository();

        if (fileUpdate.updateType() == FileUpdateType.REMOVED) {
            Log.debugf("%s/processFileUpdate: %s deleted", repo.getFullName());
            // Leave the watcher, in case the file is re-added later
            // currentConfig.set(Optional.empty());
            if (repo.getFullName().equals(ctx.getDataStore())) {
                hkConfig.clear();
            }
            return;
        }

        ScopedQueryContext qc = new ScopedQueryContext(ctx, fileUpdate.installationId(), fileUpdate.repository())
                .withExisting(github);

        processConfigUpdate(qc);
    }

    protected void processConfigUpdate(ScopedQueryContext qc) {
        GHRepository repo = qc.getRepository();
        if (repo == null) {
            Log.warnf("%s/readOrgConfig: repository not set in QueryContext", ME);
            return;
        }
        GHContent content = qc.readSourceFile(repo, HausKeeperConfig.PATH);
        if (content == null || qc.hasErrors()) {
            Log.debugf("%s/processConfigUpdate: no %s in %s", ME, HausKeeperConfig.PATH, repo.getFullName());
            return;
        }
        HausKeeperConfig cfg = qc.readYamlContent(content, HausKeeperConfig.class);
        if (cfg == null || qc.hasErrors()) {
            qc.logAndSendContextErrors("%s/processConfigUpdate: unable to parse %s in %s"
                    .formatted(ME, HausKeeperConfig.PATH, repo.getFullName()));
            return;
        }
        Log.debugf("%s/processConfigUpdate: found %s in %s", ME, HausKeeperConfig.PATH, repo.getFullName());
        hkConfig.update(qc, cfg);
    }
}
