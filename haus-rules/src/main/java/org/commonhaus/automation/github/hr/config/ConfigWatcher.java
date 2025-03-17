package org.commonhaus.automation.github.hr.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.commonhaus.automation.github.context.ContextService;
import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.commonhaus.automation.github.queue.PeriodicUpdateQueue;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.github.watchers.FileWatcher;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdate;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdateType;
import org.kohsuke.github.GHRepository;

import io.quarkus.logging.Log;

@Singleton
public class ConfigWatcher {
    static final String ME = "rulesConfig";

    @Inject
    Instance<ContextService> ctxInstance;

    @Inject
    FileWatcher fileEvents;

    @Inject
    PeriodicUpdateQueue periodicUpdate;

    final Map<String, HausRulesConfig> repoConfig = new ConcurrentHashMap<>();

    public ConfigWatcher updateConfig(String repoFullName, HausRulesConfig config) {
        repoConfig.put(repoFullName, config);
        return this;
    }

    public NoticeConfig updateNoticeConfig(String repoFullName, HausRulesConfig config) {
        updateConfig(repoFullName, config);
        return getNoticeConfig(repoFullName);
    }

    public NoticeConfig getNoticeConfig(String repoFullName) {
        HausRulesConfig config = repoConfig.get(repoFullName);
        NoticeConfig notice = config == null ? null : config.notice();
        if (notice == null) {
            return NoticeConfig.DISABLED;
        }
        return notice;
    }

    public VoteConfig updateVoteConfig(String repoFullName, HausRulesConfig config) {
        updateConfig(repoFullName, config);
        return getVoteConfig(repoFullName);
    }

    public VoteConfig getVoteConfig(String repoFullName) {
        HausRulesConfig config = repoConfig.get(repoFullName);
        VoteConfig voting = config == null ? null : config.voting();
        if (voting == null) {
            return VoteConfig.DISABLED;
        }
        return voting;
    }

    protected void repositoryDiscovered(@Observes RepositoryDiscoveryEvent repoEvent) {
        DiscoveryAction action = repoEvent.action();
        GHRepository repo = repoEvent.repository();
        String repoFullName = repo.getFullName();
        long installationId = repoEvent.installationId();

        Log.debugf("%s/repoDiscovered: %s", ME, repoFullName);

        if (action.repository() && action.added()) {
            fileEvents.watchFile(ME,
                    installationId, repoFullName, HausRulesConfig.PATH,
                    (fileUpdate) -> processConfigUpdate(fileUpdate));
        }
    }

    /**
     * Configuration file updated, read and process the new configuration.
     *
     * @param fileUpdate
     */
    protected void processConfigUpdate(FileUpdate fileUpdate) {
        // Queue the actual update to be processed at a controlled rate
        periodicUpdate.queue(ME, () -> {
            if (ctxInstance.isUnsatisfied()) {
                Log.warnf("%s/processConfigUpdate: ContextService not available", ME);
                return;
            }
            GHRepository repo = fileUpdate.repository();
            if (repo == null) {
                Log.warnf("%s/processConfigUpdate: repository not set in FileUpdate", ME);
                return;
            }
            if (fileUpdate.updateType() == FileUpdateType.REMOVED) {
                Log.debugf("%s/processConfigUpdate: %s config deleted", repo.getFullName());
                repoConfig.remove(repo.getFullName());
                return;
            }

            ContextService ctx = ctxInstance.get();
            ScopedQueryContext qc = new ScopedQueryContext(ctx, fileUpdate);
            HausRulesConfig hausRulesCfg = qc.readYamlSourceFile(repo, HausRulesConfig.PATH, HausRulesConfig.class);
            if (hausRulesCfg == null) {
                Log.debugf("%s/readHausRulesConfig: no %s in %s", ME, HausRulesConfig.PATH, repo.getFullName());
                repoConfig.remove(repo.getFullName());
                return;
            }
            Log.debugf("%s/readHausRulesConfig: found %s in %s", ME, HausRulesConfig.PATH, repo.getFullName());
            repoConfig.put(repo.getFullName(), hausRulesCfg);
        });
    }
}
