package org.commonhaus.automation.hm;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import org.commonhaus.automation.config.EmailNotification;
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
    static final String ME = "orgManager";

    private static volatile String lastRun = "";

    final AtomicReference<Optional<OrganizationConfigState>> currentConfig = new AtomicReference<>(Optional.empty());

    void startup(@Observes StartupEvent startup) {
        RouteSupplier.registerSupplier("Organization membership refreshed", () -> lastRun);
    }

    public OrganizationConfig getConfig() {
        return currentConfig.get().map(OrganizationConfigState::orgConfig).orElse(null);
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

        // We only read configuration files from repositories in the configured organization
        if (action.repository()
                && repo.getFullName().equals(mgrBotConfig.home().repositoryFullName())) {
            Log.debugf("[%s] repoDiscovered: %s main=%s", ME, action.name(), repoFullName);
            if (action.added()) {
                // main repository for configuration
                ScopedQueryContext qc = new ScopedQueryContext(ctx, installationId, repo)
                        .withExisting(repoEvent.github());

                // READ ORG CONFIG from Main repository immediately.
                readOrgConfig(qc);

                // Register watcher to monitor for org config changes
                // GHRepository is backed by a cached connection that can expire
                // use the GitHub object from the event to ensure a fresh connection
                fileEvents.watchFile(ME,
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
    public void refreshOrganizationMembership() {
        Log.info("⏰ Scheduled: refresh organization membership");

        ScopedQueryContext qc = ctx.getOrgScopedQueryContext(mgrBotConfig.home().organization());
        if (qc == null) {
            Log.debugf("[%s] refreshAccessLists: no organization installation", ME);
            return;
        }
        readOrgConfig(qc);
        lastRun = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    /**
     * Process inbound membership changes
     * Queue a reconciliation. No-need to re-read config
     */
    protected void processMembershipUpdate(String taskGroup, MembershipUpdate update) {
        // queue reconcile action: deal with bursty config updates
        periodicSync.queueReconciliation(ME, this::reconcile);
    }

    /**
     * Read organization configuration from repository.
     * Called by for file update events.
     *
     * @see #readOrgConfig(ScopedQueryContext)
     */
    protected void processFileUpdate(FileUpdate fileUpdate) {
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
        readOrgConfig(qc);
    }

    /**
     * Read organization configuration from repository.
     * Called by repositoryDiscovered and for fileEvents.
     */
    protected void readOrgConfig(ScopedQueryContext qc) {
        GHRepository repo = qc.getRepository();
        if (repo == null) {
            Log.warnf("%s/readOrgConfig: repository not set in QueryContext; errors: %s", ME,
                    qc.bundleExceptions());
            return;
        }
        GHContent content = qc.readSourceFile(repo, OrganizationConfig.PATH);
        if (content == null) {
            Log.debugf("[%s] readOrgConfig: no %s in %s; errors: %s", ME,
                    OrganizationConfig.PATH, repo.getFullName(), qc.bundleExceptions());
            return;
        }
        OrganizationConfig orgCfg = qc.readYamlContent(content, OrganizationConfig.class);
        if (orgCfg == null || qc.hasErrors()) {
            qc.logAndSendContextErrors("%s/readOrgConfig: unable to parse %s in %s"
                    .formatted(ME, OrganizationConfig.PATH, repo.getFullName()));
            return;
        }
        Log.debugf("[%s] readOrgConfig: found %s in %s", ME, OrganizationConfig.PATH, repo.getFullName());

        OrganizationConfigState newState = new OrganizationConfigState(qc.getInstallationId(), repo.getFullName(), orgCfg);
        OrganizationConfigState oldState = currentConfig.get().orElse(null);
        if (oldState != null) {
            // Find teams that were removed
            Set<String> removedTeams = new HashSet<>(oldState.teams());
            removedTeams.removeAll(newState.teams());

            // Unregister watchers for removed teams
            for (String teamName : removedTeams) {
                membershipEvents.unwatch(MembershipUpdateType.TEAM, teamName);
            }
        }
        currentConfig.set(Optional.of(newState));

        // queue reconcile action: deal with bursty config updates
        periodicSync.queueReconciliation(ME, this::reconcile);
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
        Log.debugf("[%s] reconcile: starting %s::%s", ME, configState.repoName(), OrganizationConfig.PATH);

        for (GroupMapping groupMapping : configState.orgConfig().teamMembership()) {
            if (groupMapping == null || !groupMapping.performSync()) {
                Log.debugf("[%s] reconcile: missing or empty group mapping: %s", ME, groupMapping);
                continue;
            }

            processGroupMapping(configState, groupMapping);
        }
        Log.debugf("[%s] reconcile: team membership sync complete", ME);
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
        fileEvents.unwatchAll(ME);
        membershipEvents.unwatchAll(ME);
    }

    record OrganizationConfigState(
            long installationId,
            String repoName,
            @Nonnull OrganizationConfig orgConfig,
            AtomicReference<Set<String>> teamRef) implements ConfigState {

        public OrganizationConfigState(long installationId, String repoName, OrganizationConfig orgConfig) {
            this(installationId, repoName, orgConfig, new AtomicReference<>(null));
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
                        .flatMap(x -> x.watchedTeams(repoName).stream())
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
            return "OrgConfigState{installationId=%d, repoName='%s', orgConfig=%s}"
                    .formatted(installationId, repoName, orgConfig);
        }
    }
}
