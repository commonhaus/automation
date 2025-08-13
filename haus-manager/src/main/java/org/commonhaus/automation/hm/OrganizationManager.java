package org.commonhaus.automation.hm;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import org.commonhaus.automation.config.EmailNotification;
import org.commonhaus.automation.config.RepoSource;
import org.commonhaus.automation.config.RouteSupplier;
import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent.RdePriority;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdate;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdateType;
import org.commonhaus.automation.github.watchers.MembershipWatcher.MembershipUpdate;
import org.commonhaus.automation.github.watchers.MembershipWatcher.MembershipUpdateType;
import org.commonhaus.automation.hm.config.GroupMapping;
import org.commonhaus.automation.hm.config.LatestOrgConfig;
import org.commonhaus.automation.hm.config.OrganizationConfig;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class OrganizationManager extends GroupCoordinator implements LatestOrgConfig {
    static final String ME = "🏡-org";

    final AtomicReference<Optional<OrganizationConfigState>> currentConfig = new AtomicReference<>(Optional.empty());
    private Map<String, Runnable> callbacks = new ConcurrentHashMap<>();

    void startup(@Observes StartupEvent startup) {
        RouteSupplier.registerSupplier("Organization membership refreshed", () -> lastRun);
    }

    public OrganizationConfig getConfig() {
        return currentConfig.get().map(OrganizationConfigState::orgConfig).orElse(null);
    }

    @Override
    public void notifyOnUpdate(String id, Runnable callback) {
        if (callback == null) {
            return;
        }
        callbacks.put(id, callback);
    }

    /**
     * Event handler for repository discovery.
     * Specifically look for (and monitor) organization configuration.
     */
    protected void repositoryDiscovered(
            @Observes @Priority(value = RdePriority.APP_DISCOVERY) RepositoryDiscoveryEvent repoEvent) {
        DiscoveryAction action = repoEvent.action();
        long installationId = repoEvent.installationId();
        GHRepository repo = repoEvent.repository();
        String repoFullName = repo.getFullName();

        // We only read configuration files from repositories in the configured
        // organization
        if (action.repository()
                && repoFullName.equals(mgrBotConfig.home().repositoryFullName())) {

            Log.debugf("[%s] repositoryDiscovered: %s main=%s", ME, action.name(), repoFullName);
            if (action.added()) {
                // main repository for configuration
                ScopedQueryContext qc = new ScopedQueryContext(ctx, installationId, repo)
                        .withExisting(repoEvent.github());

                // READ ORG CONFIG from Main repository immediately.
                readOrgConfig(qc);
                if (taskState.shouldRun(ME, Duration.ofHours(6))) {
                    queueReconciliation();
                } else {
                    Log.debugf("[%s] repoDiscovered: Skip eager team discovery", ME);
                }

                // Register watcher to monitor for org config changes
                // GHRepository is backed by a cached connection that can expire
                // use the GitHub object from the event to ensure a fresh connection
                fileWatcher.watchFile(ME,
                        installationId, repoFullName, OrganizationConfig.PATH,
                        (fileUpdate) -> processFileUpdate(fileUpdate));
            } else if (action.removed()) {
                // main repository for configuration
                currentConfig.set(Optional.empty());
            }
        }
    }

    /**
     * Periodically refresh/re-synchronize team access lists.
     */
    // Quartz cron expression: s m h dom mon dow year(optional)
    @Scheduled(cron = "${automation.hausManager.cron.organization:0 47 2 */3 * ?}")
    public void scheduledRefresh() {
        try {
            Log.infof("[%s] ⏰ Scheduled: begin refresh organization membership", ME);
            refreshOrganizationMembership(false);
        } catch (Throwable t) {
            ctx.logAndSendEmail(ME, "⏰ 🏡 Error running scheduled refresh of org membership", t);
        }
    }

    /**
     * Allow manual trigger from admin endpoint
     */
    public void refreshOrganizationMembership(boolean userTriggered) {
        if (!userTriggered && !taskState.shouldRun(ME, Duration.ofHours(6))) {
            Log.infof("[%s]: skip scheduled organization membership update (last run: %s)", ME, lastRun);
            return;
        }
        recordRun();
        ScopedQueryContext qc = ctx.getHomeQueryContext();
        if (qc == null) {
            Log.debugf("[%s] refreshOrganizationMembership: no organization installation", ME);
            return;
        }
        if (readOrgConfig(qc)) {
            queueReconciliation();
        } else if (qc.hasErrors()) {
            qc.logAndSendContextErrors(
                    "Unable to read %s in %s".formatted(OrganizationConfig.PATH,
                            mgrBotConfig.home().repositoryFullName()));
        }
    }

    /**
     * Process inbound membership changes
     * Queue a reconciliation. No-need to re-read config
     */
    protected void processMembershipUpdate(String taskGroup, MembershipUpdate update) {
        recordRun();
        // queue reconcile action: deal with bursty config updates
        updateQueue.queueReconciliation(ME, this::reconcile);
    }

    @Override
    protected void processRepoSourceUpdate(String taskGroup, RepoSource repoSource) {
        // queue reconcile action: deal with bursty config updates
        updateQueue.queue(ME, () -> {
            OrganizationConfigState configState = currentConfig.get().orElse(null);
            if (configState == null || !configState.performSync() || configState.orgConfig().teamMembership() == null) {
                Log.debugf("[%s] processRepoSourceUpdate: configuration not available or team sync not enabled: %s", ME,
                        configState);
                return;
            }
            Log.debugf("[%s] processRepoSourceUpdate: %s", ME, repoSource);
            OrganizationConfig orgConfig = configState.orgConfig();
            List<GroupMapping> mappings = orgConfig.teamMembership().stream()
                    .filter(mapping -> repoSource.equals(mapping.source()))
                    .toList();
            for (var mapping : mappings) {
                processGroupMapping(configState, mapping);
            }
        });
    }

    /**
     * Read organization configuration from repository.
     * Called by for file update events.
     *
     * @see #readOrgConfig(ScopedQueryContext)
     */
    protected void processFileUpdate(FileUpdate fileUpdate) {
        recordRun();

        GitHub github = fileUpdate.github();
        GHRepository repo = fileUpdate.repository();

        if (fileUpdate.updateType() == FileUpdateType.REMOVED) {
            Log.debugf("[%s] processFileUpdate: %s deleted", repo.getFullName());
            currentConfig.set(Optional.empty());
            // TODO: clean up associated resources.
            // Leave the watcher, in case the file is re-added later
            // currentConfig.set(Optional.empty());
            return;
        }

        ScopedQueryContext qc = new ScopedQueryContext(ctx, fileUpdate.installationId(), repo)
                .withExisting(github);
        if (readOrgConfig(qc)) {
            queueReconciliation();
        } else if (qc.hasErrors()) {
            qc.logAndSendContextErrors(
                    "Unable to read %s in %s".formatted(OrganizationConfig.PATH,
                            mgrBotConfig.home().repositoryFullName()));
        }
    }

    /**
     * Read organization configuration from repository.
     * Called by repositoryDiscovered and for fileEvents.
     */
    protected boolean readOrgConfig(ScopedQueryContext qc) {
        GHRepository repo = qc.getRepository();
        if (repo == null) {
            Log.warnf("[%s] readOrgConfig: repository not set in QueryContext; errors: %s", ME,
                    qc.bundleExceptions());
            return false;
        }
        GHContent content = qc.readSourceFile(repo, OrganizationConfig.PATH);
        if (content == null) {
            Log.debugf("[%s] readOrgConfig: no %s in %s; errors: %s", ME,
                    OrganizationConfig.PATH, repo.getFullName(), qc.bundleExceptions());
            return false;
        }
        OrganizationConfig orgCfg = qc.readYamlContent(content, OrganizationConfig.class);
        if (orgCfg == null || qc.hasErrors()) {
            qc.logAndSendEmail("readOrgConfig: unable to parse configuraton",
                    "Unable to parse %s in %s".formatted(OrganizationConfig.PATH, repo.getFullName()),
                    qc.bundleExceptions(),
                    orgCfg.emailNotifications());
            return false;
        }
        Log.debugf("[%s] readOrgConfig: found %s in %s", ME, OrganizationConfig.PATH, repo.getFullName());

        OrganizationConfigState newState = new OrganizationConfigState(
                qc.getInstallationId(), repo.getFullName(), orgCfg);
        OrganizationConfigState oldState = currentConfig.get().orElse(null);
        if (oldState != null) {
            if (oldState.orgConfig().equals(newState.orgConfig())) {
                Log.debugf("[%s] readOrgConfig: no changes in %s", ME, OrganizationConfig.PATH);
                return false;
            }

            // Find teams that were removed
            Set<String> removedTeams = new HashSet<>(oldState.teams());
            removedTeams.removeAll(newState.teams());

            // Unregister watchers for removed teams
            for (String teamName : removedTeams) {
                membershipEvents.unwatch(MembershipUpdateType.TEAM, teamName);
            }

            // Clean up GroupMapping source file watchers for changed/removed mappings
            Set<RepoSource> oldSources = oldState.orgConfig().teamMembership().stream()
                    .map(GroupMapping::source)
                    .filter(x -> x != null && !x.isEmpty())
                    .collect(Collectors.toSet());

            for (GroupMapping groupMapping : newState.orgConfig().teamMembership()) {
                oldSources.remove(groupMapping.source());
            }

            for (RepoSource oldSource : oldSources) {
                unwatchRepoSource(oldState, oldSource);
            }
        }
        currentConfig.set(Optional.of(newState));
        return true;
    }

    private void queueReconciliation() {
        // queue reconcile action: deal with bursty config updates
        updateQueue.queueReconciliation(ME, this::reconcile);

        // Queue callbacks for config consumers
        for (var callback : callbacks.entrySet()) {
            updateQueue.queueReconciliation(callback.getKey(), callback.getValue());
        }
    }

    /**
     * Reconcile team membership with org configuration (CONTACTS.yaml)
     * Review collected configuration and perform required actions
     */
    public void reconcile() {
        OrganizationConfigState configState = currentConfig.get().orElse(null);
        if (configState == null || !configState.performSync()) {
            Log.debugf("[%s] reconcile: configuration not available or team sync not enabled: %s", ME, configState);
            return;
        }
        Log.debugf("[%s] reconcile: start %s::%s", ME, configState.repoFullName(), OrganizationConfig.PATH);

        for (GroupMapping groupMapping : configState.orgConfig().teamMembership()) {
            if (groupMapping == null || !groupMapping.performSync()) {
                Log.debugf("[%s] reconcile: missing or empty group mapping: %s", ME, groupMapping);
                continue;
            }
            // Register watcher for the source file so we get notified when it changes
            watchRepoSource(configState, groupMapping.source());
            processGroupMapping(configState, groupMapping);
        }
        Log.debugf("[%s] reconcile: end %s::%s", ME, configState.repoFullName(), OrganizationConfig.PATH);
    }

    protected String me() {
        return ME;
    }

    /**
     * Hard-reset of the organization manager.
     * This is useful for testing.
     */
    protected void reset() {
        currentConfig.set(Optional.empty());
        fileWatcher.unwatchAll(ME);
        membershipEvents.unwatchAll(ME);
    }

    record OrganizationConfigState(
            long installationId,
            String repoFullName,
            @Nonnull OrganizationConfig orgConfig,
            Set<RepoSource> sources,
            AtomicReference<Set<String>> teamRef) implements ConfigState {

        public OrganizationConfigState(long installationId, String repoName, OrganizationConfig orgConfig) {
            this(installationId, repoName, orgConfig, new HashSet<>(), new AtomicReference<>(null));
        }

        public boolean add(RepoSource source) {
            return sources.add(source);
        }

        public boolean remove(RepoSource source) {
            return sources.remove(source);
        }

        public boolean performSync() {
            return orgConfig.teamMembership() != null && orgConfig.teamMembership().stream()
                    .anyMatch(GroupMapping::performSync);
        }

        public Set<String> teams() {
            Set<String> teams = this.teamRef.get();
            if (teams == null) {
                teams = new HashSet<>();
                teams.addAll(orgConfig.teamMembership().stream()
                        .flatMap(x -> x.watchedTeams(repoFullName).stream())
                        .toList());
                this.teamRef.set(teams);
            }
            return teams;
        }

        public EmailNotification emailNotifications() {
            return orgConfig.emailNotifications();
        }

        public String taskGroup() {
            return ME;
        }

        @Override
        public String toString() {
            return "OrgConfigState{installationId=%d, repoFullName='%s', orgConfig=%s}"
                    .formatted(installationId, repoFullName, orgConfig);
        }
    }
}
