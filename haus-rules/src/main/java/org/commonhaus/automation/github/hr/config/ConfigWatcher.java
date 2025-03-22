package org.commonhaus.automation.github.hr.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent.RdePriority;
import org.commonhaus.automation.github.hr.AppContextService;
import org.commonhaus.automation.github.queue.PeriodicUpdateQueue;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.github.watchers.FileWatcher;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdateType;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHRepository;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class ConfigWatcher {
    static final String ME = "rulesConfig";

    @Inject
    AppContextService ctx;

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

    protected void repositoryDiscovered(
            @Observes @Priority(value = RdePriority.APP_DISCOVERY) RepositoryDiscoveryEvent repoEvent) {
        Log.infof("⚙️ 🗳️ ConfigWatcher.repositoryDiscovered: %s", repoEvent.repository().getFullName());

        DiscoveryAction action = repoEvent.action();
        GHRepository repo = repoEvent.repository();
        String repoFullName = repo.getFullName();
        long installationId = repoEvent.installationId();

        Log.debugf("%s/repoDiscovered: %s", ME, repoFullName);
        readConfiguration(repoEvent.installationId(), repoEvent.repository(), null);

        if (action.repository() && action.added()) {
            fileEvents.watchFile(ME,
                    installationId, repoFullName, HausRulesConfig.PATH,
                    (fileUpdate) -> readConfiguration(fileUpdate.installationId(), fileUpdate.repository(),
                            fileUpdate.updateType()));
        }
    }

    // Quartz cron expression: s m h dom mon dow year(optional)
    @Scheduled(cron = "${automation.hausRules.cron.config:0 17 2 * * ?}")
    void refreshConfig() {
        Log.info("⏰ ⚙️ Scheduled: refresh config");
        fileEvents.refresh(ctx, ME);
    }

    protected void readConfiguration(long installationId, GHRepository repo, FileUpdateType updateType) {
        if (repo == null) {
            Log.warnf("%s/processConfigUpdate: repository not set in FileUpdate", ME);
            return;
        }
        if (updateType == FileUpdateType.REMOVED) {
            Log.debugf("%s/processConfigUpdate: %s config deleted", repo.getFullName());
            repoConfig.remove(repo.getFullName());
            return;
        }

        ScopedQueryContext qc = new ScopedQueryContext(ctx, installationId, repo);
        GHContent content = qc.readSourceFile(repo, HausRulesConfig.PATH);
        if (content == null || qc.hasErrors()) {
            Log.debugf("%s/readHausRulesConfig: no %s in %s", ME, HausRulesConfig.PATH, repo.getFullName());
            repoConfig.remove(repo.getFullName());
            return;
        }
        HausRulesConfig hausRulesCfg = qc.readYamlContent(content, HausRulesConfig.class);
        if (hausRulesCfg == null || qc.hasErrors()) {
            qc.logAndSendContextErrors("%s/readHausRulesConfig: unable to parse %s in %s"
                    .formatted(ME, HausRulesConfig.PATH, repo.getFullName()));
            repoConfig.remove(repo.getFullName());
            return;
        }

        Log.debugf("%s/readHausRulesConfig: found %s in %s", ME, HausRulesConfig.PATH, repo.getFullName());
        repoConfig.put(repo.getFullName(), hausRulesCfg);
    }
}
