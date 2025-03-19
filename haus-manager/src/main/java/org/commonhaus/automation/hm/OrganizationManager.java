package org.commonhaus.automation.hm;

import static org.commonhaus.automation.github.context.QueryContext.toOrganizationName;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import org.commonhaus.automation.config.EmailNotification;
import org.commonhaus.automation.config.RepoSource;
import org.commonhaus.automation.config.RouteSupplier;
import org.commonhaus.automation.github.context.GitHubTeamService;
import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent.RdePriority;
import org.commonhaus.automation.github.queue.PeriodicUpdateQueue;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.github.watchers.FileWatcher;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdate;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdateType;
import org.commonhaus.automation.github.watchers.MembershipWatcher;
import org.commonhaus.automation.github.watchers.MembershipWatcher.MembershipUpdate;
import org.commonhaus.automation.github.watchers.MembershipWatcher.MembershipUpdateType;
import org.commonhaus.automation.hm.config.LatestOrgConfig;
import org.commonhaus.automation.hm.config.ManagerBotConfig;
import org.commonhaus.automation.hm.config.OrganizationConfig;
import org.commonhaus.automation.hm.config.OrganizationConfig.GroupMapping;
import org.commonhaus.automation.hm.config.OrganizationConfig.OrgDefaults;
import org.commonhaus.automation.hm.config.OrganizationConfig.SyncToTeams;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class OrganizationManager implements LatestOrgConfig {
    static final String ME = "orgManager";

    private static volatile String lastRun = "";

    final FileWatcher fileEvents;
    final MembershipWatcher membershipEvents;
    final ManagerBotConfig mgrBotConfig;
    final AppContextService ctx;
    final PeriodicUpdateQueue periodicSync;
    final GitHubTeamService teamSyncService;

    final AtomicReference<Optional<OrganizationConfigState>> currentConfig = new AtomicReference<>(Optional.empty());

    public OrganizationManager(AppContextService appContext, ManagerBotConfig mgrBotConfig,
            FileWatcher fileEvents, MembershipWatcher membershipEvents,
            PeriodicUpdateQueue periodicSync, GitHubTeamService teamSyncService) {
        this.ctx = appContext;
        this.fileEvents = fileEvents;
        this.membershipEvents = membershipEvents;
        this.mgrBotConfig = mgrBotConfig;
        this.periodicSync = periodicSync;
        this.teamSyncService = teamSyncService;

        Log.debugf("%s MAIN: %s / %s", ME,
                mgrBotConfig.home().organization(), mgrBotConfig.home().repository());

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
        GHRepository repo = repoEvent.repository();
        String repoFullName = repo.getFullName();
        String orgName = toOrganizationName(repoFullName);
        long installationId = repoEvent.installationId();

        // We only read configuration files from repositories in the configured organization
        if (action.repository()
                && orgName.equals(mgrBotConfig.home().organization())
                && repo.getFullName().equals(mgrBotConfig.home().repositoryFullName())) {
            if (action.added()) {
                // main repository for configuration
                Log.debugf("%s/repoDiscovered: added main=%s", ME, repoFullName);
                ScopedQueryContext qc = new ScopedQueryContext(ctx, installationId, repo)
                        .withExisting(repoEvent.github());

                // read org config from the main repository
                periodicSync.queue(ME, () -> readOrgConfig(qc));

                // Register watcher to monitor for org config changes
                // GHRepository is backed by a cached connection that can expire
                // use the GitHub object from the event to ensure a fresh connection
                fileEvents.watchFile(ME,
                        installationId, repoFullName, OrganizationConfig.PATH,
                        (fileUpdate) -> processFileUpdate(fileUpdate));
            } else if (action.removed()) {
                // main repository for configuration
                Log.debugf("%s/repoDiscovered: removed main=%s", ME, repoFullName);
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
            Log.debugf("%s/refreshAccessLists: no organization installation", ME);
            return;
        }
        readOrgConfig(qc);
        lastRun = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    /**
     * Process inbound membership changes
     * Queue a reconcilation. No-need to re-read config
     */
    protected void processMembershipUpdate(MembershipUpdate update) {
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
            Log.debugf("%s/processFileUpdate: %s deleted", repo.getFullName());
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
     *
     * @param installationId
     * @param repoFullName
     * @param github
     */
    protected void readOrgConfig(ScopedQueryContext qc) {
        GHRepository repo = qc.getRepository();
        if (repo == null) {
            Log.warnf("%s/readOrgConfig: repository not set in QueryContext", ME);
            return;
        }
        OrganizationConfig orgCfg = qc.readYamlSourceFile(repo, OrganizationConfig.PATH, OrganizationConfig.class);
        if (orgCfg == null) {
            Log.debugf("%s/readOrgConfig: no %s in %s", ME, OrganizationConfig.PATH, repo.getFullName());
            return;
        }
        Log.debugf("%s/readOrgConfig: found %s in %s", ME, OrganizationConfig.PATH, repo.getFullName());

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
            Log.debugf("%s::reconcile: configuration not available or team sync not enabled: %s", ME, configState);
            return;
        }
        Log.debugf("%s::reconcile: starting %s::%s", ME, configState.repoName(), OrganizationConfig.PATH);

        GroupMapping groupMapping = configState.groupMapping();
        if (groupMapping == null || !groupMapping.performSync()) {
            Log.debugf("%s::reconcile: missing or empty group mapping: %s", ME, groupMapping);
            return;
        }

        // Dry run for the group configuration and/or dry run for everything
        boolean isDryRun = groupMapping.dryRun() || ctx.isDryRun();

        ScopedQueryContext orgQc = new ScopedQueryContext(ctx,
                configState.installationId(), configState.repoName());

        // Find and read the source file (CONTACTS.yaml)
        // First: find the repository
        RepoSource source = groupMapping.source();
        String sourceRepoName = source.repository() == null ? configState.repoName() : source.repository();
        ScopedQueryContext sourceQc = orgQc.forOrganization(sourceRepoName, isDryRun);
        if (sourceQc == null) {
            Log.warnf("%s::reconcile: No installation for %s; skipping org", ME, sourceRepoName);
            return;
        }

        GHRepository sourceRepo = sourceQc == null ? null : sourceQc.getRepository(sourceRepoName);
        if (sourceRepo == null) {
            Log.warnf("%s::reconcile: source repository %s not found", ME, sourceRepoName);
            return;
        }
        // read the file from the repository
        JsonNode sourceData = sourceQc.readYamlSourceFile(sourceRepo, source.filePath());
        if (sourceData == null) {
            Log.warnf("%s::reconcile: source file %s is empty or could not be read", ME, source.filePath());
            return;
        }

        OrgDefaults defaults = groupMapping.defaults();

        // Iterate over configurations in the GroupMapping to decide
        // which teams to sync and how to sync them
        // Use the source data (e.g. CONTACTS.yaml) to identify team members
        for (var entry : groupMapping.sync().entrySet()) {
            String groupName = entry.getKey();
            SyncToTeams sync = entry.getValue();
            String field = sync.field(defaults);

            JsonNode sourceTeamMemberList = sourceData.get(groupName);
            if (sourceTeamMemberList != null && sourceTeamMemberList.isArray()) {
                Log.debugf("%s::reconcile: field %s from %s to %s", ME, field, groupName, sync.teams());

                // Populate list of expected logins with those we intend to preserve
                Set<String> expectedLogins = new HashSet<>(sync.preserveUsers(defaults));
                // Find the users listed in the source data
                for (JsonNode member : sourceTeamMemberList) {
                    String login = member.get(field).asText();
                    if (login != null && login.matches("^[a-zA-Z0-9-]+$")) {
                        expectedLogins.add(login);
                    }
                }

                for (String targetTeam : sync.teams()) {
                    try {
                        doSyncTeamMembers(sourceQc, targetTeam, expectedLogins, sync.ignoreUsers(),
                                isDryRun, configState.emailNotifications());
                    } catch (Throwable t) {
                        ctx.logAndSendEmail(ME, "Error syncing team members", t,
                                orgQc.getErrorAddresses(configState.emailNotifications()));
                    }
                }

            } else {
                Log.debugf("%s::reconcile: group %s not found in %s", ME, groupName, sourceData);
            }
        }
        Log.debugf("%s::reconcile: team membership sync complete", ME);
    }

    /**
     * Synchronize team members with expected logins.
     *
     * @param qc
     * @param targetTeam
     * @param expectedLogins
     * @param ignoreUsers
     * @param isDryRun
     */
    void doSyncTeamMembers(ScopedQueryContext orgQc, String targetTeam,
            Set<String> expectedLogins, List<String> ignoreUsers,
            boolean isDryRun, EmailNotification emailNotifications) {

        // We need to find the right installation id
        // so we can read/make changes to the team
        ScopedQueryContext teamQc = orgQc.forOrganization(targetTeam, isDryRun);
        if (teamQc == null) {
            Log.warnf("%s/doSyncTeamMembers: No installation for %s; skipping team sync", ME, targetTeam);
            return;
        }

        // Delegate to the shared utility
        teamSyncService.syncMembers(teamQc, targetTeam, expectedLogins, ignoreUsers, isDryRun, emailNotifications);

        // Register watcher for team membership changes
        membershipEvents.watchMembers(ME,
                teamQc.getInstallationId(),
                MembershipUpdateType.TEAM,
                targetTeam,
                this::processMembershipUpdate);
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

    record OrganizationConfigState(long installationId, String repoName, @Nonnull OrganizationConfig orgConfig,
            Set<String> teams) {

        public OrganizationConfigState(long installationId, String repoName, OrganizationConfig orgConfig) {
            this(installationId, repoName, orgConfig, new HashSet<>());
        }

        public Set<String> teams() {
            if (teams.isEmpty()) {
                orgConfig.teamMembership().sync().values().stream()
                        .flatMap(x -> x.teams().stream())
                        .map(this::toFullTeamName)
                        .forEach(teams::add);
            }
            return teams;
        }

        public String sourceRepository() {
            return orgConfig.teamMembership() == null ? null : orgConfig.teamMembership().source().repository();
        }

        /**
         * Ensure that the team name is fully qualified with the organization name.
         * If the team name is already fully qualified, it is returned as is.
         * Otherwise, the organization _of the source file_ is prepended to the team name.
         *
         * @param teamName
         * @return
         */
        public String toFullTeamName(String teamName) {
            if (teamName.contains("/")) {
                return teamName;
            }
            String sourceOrg = toOrganizationName(sourceRepository());
            if (sourceOrg == null) { // unexpected, but don't fail.
                return teamName;
            }
            return "%s/%s".formatted(sourceOrg, teamName);
        }

        public boolean performSync() {
            return orgConfig.teamMembership() != null && orgConfig.teamMembership().performSync();
        }

        public GroupMapping groupMapping() {
            return orgConfig.teamMembership();
        }

        public EmailNotification emailNotifications() {
            return orgConfig.emailNotifications();
        }

        @Override
        public String toString() {
            return "OrgConfigState{installationId=%d, repoName='%s', orgConfig=%s}"
                    .formatted(installationId, repoName, orgConfig);
        }
    }
}
