package org.commonhaus.automation.hm;

import static org.commonhaus.automation.github.context.GitHubTeamService.refreshCollaborators;
import static org.commonhaus.automation.github.context.GitHubTeamService.refreshTeam;
import static org.commonhaus.automation.github.context.QueryContext.toOrganizationName;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.config.RouteSupplier;
import org.commonhaus.automation.github.context.GitHubTeamService;
import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.commonhaus.automation.github.queue.PeriodicUpdateQueue;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.github.watchers.FileWatcher;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdate;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdateType;
import org.commonhaus.automation.github.watchers.MembershipWatcher;
import org.commonhaus.automation.github.watchers.MembershipWatcher.MembershipUpdate;
import org.commonhaus.automation.github.watchers.MembershipWatcher.MembershipUpdateType;
import org.commonhaus.automation.hm.config.ManagerBotConfig;
import org.commonhaus.automation.hm.config.ProjectConfig;
import org.commonhaus.automation.hm.config.ProjectConfig.TeamAccess;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class ProjectAccessManager {
    static final String ME = "projectAccess";

    private static volatile String lastRun = "never";

    @Inject
    AppContextService ctx;

    @Inject
    FileWatcher fileEvents;

    @Inject
    MembershipWatcher membershipEvents;

    @Inject
    ManagerBotConfig mgrBotConfig;

    @Inject
    PeriodicUpdateQueue periodicSync;

    @Inject
    GitHubTeamService teamSyncService;

    // flat map of tracked resources to the task group that manages them
    final Map<String, String> resourceToTaskGroup = new ConcurrentHashMap<>();
    // flat map of task group to its current state
    final Map<String, ProjectConfigState> taskGroupToState = new ConcurrentHashMap<>();

    void startup(@Observes StartupEvent startup) {
        RouteSupplier.registerSupplier("Project access refreshed", () -> lastRun);
    }

    private static final String teamToResource(String teamFullName) {
        return "team-" + teamFullName;
    }

    private static final String repoToResource(String repoFullName) {
        return "repo-" + repoFullName;
    }

    public static final String resourceToFullName(String resource) {
        return resource.substring(5);
    }

    /**
     * Periodically refresh/re-synchronize team access lists.
     */
    @Scheduled(cron = "${automation.hausManager.cron:13 27 */3 * * ?}")
    public void refreshAccessLists() {
        for (String resourceKey : resourceToTaskGroup.keySet()) {
            String fullName = resourceToFullName(resourceKey);
            // Clear cache to force re-fetch on next access
            if (resourceKey.startsWith("team-")) {
                refreshTeam(fullName);
            } else {
                refreshCollaborators(fullName);
            }
        }
        // Queue reconciliation for all known task groups
        for (ProjectConfigState state : taskGroupToState.values()) {
            ScopedQueryContext qc = new ScopedQueryContext(ctx, state.installationId(), state.repoFullName());
            readProjectConfig(ME, qc);
        }
        lastRun = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    /**
     * Event handler for repository discovery.
     */
    protected void repositoryDiscovered(@Observes RepositoryDiscoveryEvent repoEvent) {
        DiscoveryAction action = repoEvent.action();
        GHRepository repo = repoEvent.repository();
        String repoFullName = repo.getFullName();
        String orgName = toOrganizationName(repoFullName);

        Log.debugf("%s/repositoryDiscovered (%s): %s", ME, repoEvent.action(), repoFullName);
        long installationId = repoEvent.installationId();

        // We only read configuration files from repositories in the configured organization
        if (action.repository() && orgName.equals(mgrBotConfig.configOrganization())) {
            final String taskGroup = "%s-%s".formatted(ME, repoFullName);

            if (action.added()) {
                ScopedQueryContext qc = new ScopedQueryContext(ctx, installationId, repo)
                        .withExisting(repoEvent.github());

                periodicSync.queue(taskGroup, () -> readProjectConfig(taskGroup, qc));

                // Register watcher to monitor for org config changes
                // GHRepository is backed by a cached connection that can expire
                // use the GitHub object from the event to ensure a fresh connection
                fileEvents.watchFile(taskGroup,
                        installationId, repoFullName, ProjectConfig.PATH,
                        (fileUpdate) -> processFileUpdate(taskGroup, fileUpdate));

            } else if (action.removed()) {
                // Remove any state associated with the repository (all watcher cleanup handled separately)
                resourceToTaskGroup.remove(repoToResource(repoFullName));
                ProjectConfigState state = taskGroupToState.remove(taskGroup);
                if (state != null) {
                    String sourceTeam = state.sourceTeam();
                    if (sourceTeam != null) {
                        resourceToTaskGroup.remove(teamToResource(sourceTeam));
                    }
                }
            }
        }
    }

    /**
     * Process inbound membership changes
     * Queue a reconcilation. No-need to re-read config
     */
    protected void processMembershipUpdate(String taskGroup, MembershipUpdate update) {
        Log.debugf("%s/processMembershipUpdate: %s", ME, update);
        ProjectConfigState newState = taskGroupToState.get(taskGroup);
        if (newState == null) {
            Log.warnf("%s/processMembershipUpdate: no state for %s", ME, taskGroup);
            return;
        }
        // queue reconcile action: deal with bursty config updates
        periodicSync.queueReconciliation(ME, () -> reconcile(taskGroup));
    }

    /**
     * Read project configuration from repository.
     * Called for file update events
     *
     * @see #readProjectConfig(ScopedQueryContext)
     */
    protected void processFileUpdate(String taskGroup, FileUpdate fileUpdate) {
        ScopedQueryContext qc = new ScopedQueryContext(ctx, fileUpdate);

        if (fileUpdate.updateType() == FileUpdateType.REMOVED) {
            String repoFullName = fileUpdate.repository().getFullName();
            Log.debugf("%s/processFileUpdate: %s deleted", taskGroup, repoFullName);
            // The watcher will notify if the file is re-added
            // TODO: clean up associated resources.
            return;
        }

        readProjectConfig(taskGroup, qc);
    }

    /**
     * Read organization configuration from repository.
     * Called by repositoryDiscovered, on file events, and periodic sync
     *
     * @param installationId
     * @param repoFullName
     * @param github
     */
    protected void readProjectConfig(String taskGroup, ScopedQueryContext qc) {
        // The repository containing the (added/modified) file must be present in the query context
        GHRepository repo = qc.getRepository();
        if (repo == null) {
            Log.warnf("%s/readProjectConfig: repository not set in QueryContext", taskGroup);
            return;
        }
        ProjectConfig projectConfig = qc.readYamlSourceFile(repo, ProjectConfig.PATH, ProjectConfig.class);
        if (projectConfig == null) {
            Log.debugf("%s/readProjectConfig: no %s in %s", ME, ProjectConfig.PATH, repo.getFullName());
            return;
        }
        Log.debugf("%s/readProjectConfig: found %s in %s", ME, ProjectConfig.PATH, repo.getFullName());

        ProjectConfigState newState = new ProjectConfigState(taskGroup,
                repo.getFullName(), qc.getInstallationId(), projectConfig);

        ProjectConfigState oldState = taskGroupToState.get(taskGroup);
        if (oldState != null && oldState.sourceTeamHasChanged(newState)) {
            // The source team could change
            Log.debugf("%s/readProjectConfig: remove old state source -- %s", taskGroup, oldState.sourceTeam());
            resourceToTaskGroup.remove(repoToResource(oldState.sourceTeam()));
            membershipEvents.unwatch(MembershipUpdateType.TEAM, oldState.sourceTeam());
        }

        taskGroupToState.put(taskGroup, newState);

        // queue reconcile action: deal with bursty config updates
        periodicSync.queueReconciliation(taskGroup, () -> reconcile(taskGroup));
    }

    /**
     * Reconcile team membership with project/org configuration
     * Review collected configuration and perform required actions
     *
     * @param string
     */
    public void reconcile(String taskGroup) {
        ProjectConfigState newState = taskGroupToState.get(taskGroup);
        Log.debugf("%s/reconcile: team membership sync; %s", newState.taskGroup(), newState.projectConfig());

        ProjectConfig projectConfig = newState.projectConfig();
        if (!projectConfig.enabled() || projectConfig.teamAccess() == null) {
            Log.infof("%s/reconcile: configuration disabled or teamAccess missing; skipping %s", newState.taskGroup(),
                    projectConfig);
            return;
        }

        ScopedQueryContext projectQc = new ScopedQueryContext(ctx, newState.installationId(), newState.repoFullName());

        boolean isDryRun = projectConfig.dryRun() || ctx.isDryRun();
        TeamAccess teamAccess = projectConfig.teamAccess();
        String sourceTeamName = teamAccess.source();

        // Find the source team and its members (from another organization)
        ScopedQueryContext teamQc = projectQc.forOrganization(sourceTeamName, isDryRun);
        if (teamQc == null) {
            Log.warnf("[%s] No installation associated with team %s; skipping team sync", ME, sourceTeamName);
            return;
        }

        // Get team members
        Set<String> sourceLogins = teamSyncService.getTeamLogins(teamQc, sourceTeamName);

        // Find the repository where the configuration file is located
        String repoFullName = newState.repoFullName();
        GHRepository repo = projectQc.getRepository(repoFullName);

        GHOrganization.RepositoryRole collaboratorRole = GHOrganization.RepositoryRole.from(GHOrganization.Permission.PUSH);

        // Make sure source team members are outside collaborators on the repository that contains
        // the configuration file.
        teamSyncService.syncCollaborators(projectQc, repo, collaboratorRole,
                sourceLogins, teamAccess.ignoreUsers(),
                isDryRun, projectConfig.emailNotifications());

        // Update/keep references: sourceTeam -> taskGroup; taskGroup -> state
        resourceToTaskGroup.put(sourceTeamName, newState.taskGroup());
        taskGroupToState.put(newState.taskGroup(), newState);

        // Note: taskGroup is tied to the repo where the config file lives
        // Watch for collaborator changes on the repository
        membershipEvents.watchMembers(newState.taskGroup(),
                projectQc.getInstallationId(),
                MembershipUpdateType.COLLABORATOR,
                repoFullName,
                (update) -> processMembershipUpdate(newState.taskGroup(), update));

        // Watch for changes on the source team
        membershipEvents.watchMembers(newState.taskGroup(),
                teamQc.getInstallationId(),
                MembershipUpdateType.TEAM,
                sourceTeamName,
                (update) -> processMembershipUpdate(newState.taskGroup(), update));

        Log.debugf("%s/reconcile: team membership sync complete: %s", newState.taskGroup(), newState.projectConfig());
    }

    static record ProjectConfigState(
            String taskGroup,
            String repoFullName,
            long installationId,
            ProjectConfig projectConfig) {

        public String sourceTeam() {
            TeamAccess myAccess = projectConfig.teamAccess();
            return myAccess != null ? myAccess.source() : null;
        }

        public boolean sourceTeamHasChanged(ProjectConfigState other) {
            TeamAccess myAccess = projectConfig.teamAccess();
            if (myAccess == null || myAccess.source() == null) {
                return false; // nothing to clean up
            }
            TeamAccess otherAccess = other.projectConfig.teamAccess();
            return otherAccess == null || !myAccess.source().equals(otherAccess.source());
        }

        public String[] errors() {
            return projectConfig().emailNotifications().errors();
        }
    }

    /**
     * Hard-reset of the organization manager.
     * This is useful for testing.
     */
    protected void reset() {
        fileEvents.unwatchAll(ME);
        membershipEvents.unwatchAll(ME);
        resourceToTaskGroup.clear();
        taskGroupToState.clear();
    }
}
