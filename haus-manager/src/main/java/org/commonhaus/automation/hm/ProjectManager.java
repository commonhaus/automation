package org.commonhaus.automation.hm;

import static org.commonhaus.automation.github.context.GitHubQueryContext.toOrganizationName;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import org.commonhaus.automation.config.EmailNotification;
import org.commonhaus.automation.config.RepoSource;
import org.commonhaus.automation.config.RouteSupplier;
import org.commonhaus.automation.github.discovery.BootstrapDiscoveryEvent;
import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent.RdePriority;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdate;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdateType;
import org.commonhaus.automation.github.watchers.MembershipWatcher.MembershipUpdate;
import org.commonhaus.automation.github.watchers.MembershipWatcher.MembershipUpdateType;
import org.commonhaus.automation.hm.config.GroupMapping;
import org.commonhaus.automation.hm.config.LatestProjectConfig;
import org.commonhaus.automation.hm.config.ProjectConfig;
import org.commonhaus.automation.hm.config.ProjectConfig.CollaboratorSync;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class ProjectManager extends GroupCoordinator implements LatestProjectConfig {
    static final String ME = "🌳-project";
    static final ProjectConfigState EMPTY = new ProjectConfigState(null, null, null, 0, null);

    // flat map of task group to its current state
    final Map<String, ProjectConfigState> taskGroupToState = new ConcurrentHashMap<>();
    final Map<String, ProjectConfigListener> callbacks = new ConcurrentHashMap<>();

    void startup(@Observes StartupEvent startup) {
        RouteSupplier.registerSupplier("Project access refreshed", () -> lastRun);
    }

    static String repoNametoTaskGroup(String repoFullName) {
        return "cfg#" + repoFullName;
    }

    static String taskGroupToRepo(String taskGroup) {
        return taskGroup.substring(4);
    }

    public void notifyOnUpdate(String id, ProjectConfigListener listener) {
        if (id == null || id.isBlank() || listener == null) {
            return;
        }
        callbacks.put(id, listener);
    }

    @Override
    public Collection<ProjectConfigState> getAllProjects() {
        return taskGroupToState.values();
    }

    @Override
    public ProjectConfigState getProjectConfigState(String repoFullName) {
        var taskGroup = repoNametoTaskGroup(repoFullName);
        return taskGroupToState.get(taskGroup);
    }

    /**
     * Periodically refresh/re-synchronize team access lists.
     */
    // Quartz cron expression: s m h dom mon dow year(optional)
    @Scheduled(cron = "${automation.hausManager.cron.projects:0 47 4 */3 * ?}")
    public void scheduledRefresh() {
        try {
            Log.infof("[%s] ⏰ Scheduled: begin refresh config", ME);
            refreshConfig(false);
        } catch (Throwable t) {
            ctx.logAndSendEmail(ME, "⏰ 🌳 Error running scheduled config refresh", t);
        }
    }

    /**
     * Allow manual trigger from admin endpoint
     */
    public void refreshConfig(boolean userTriggered) {
        if (!userTriggered && !taskState.shouldRun(ME, Duration.ofHours(6))) {
            Log.infof("[%s]: skip scheduled project config update (last run: %s)", ME, lastRun);
            return;
        }
        recordRun();

        // Queue reconciliation for all known task groups
        for (var entry : taskGroupToState.entrySet()) {
            String taskGroup = entry.getKey();
            var state = entry.getValue();

            String repoFullName = taskGroupToRepo(taskGroup);
            ScopedQueryContext qc = state == null || state == EMPTY
                    ? ctx.getOrgScopedQueryContext(repoFullName)
                    : new ScopedQueryContext(ctx, state.installationId(), repoFullName);
            if (qc == null) {
                Log.warnf("[%s] %s: no org context for %s", ME, taskGroup, repoFullName);
                continue;
            }
            // do this in chunks to space the work out..
            updateQueue.queue(taskGroup, () -> readProjectConfig(taskGroup, qc));
        }
        // After queuing all project config reads,
        // queue reconciliation tasks to sync team membership
        // this grouping matters: avoid management conflicts
        queueReconciliation();
    }

    /**
     * Event handler for repository discovery.
     */
    protected void repositoryDiscovered(
            @Observes @Priority(value = RdePriority.APP_DISCOVERY + 2) RepositoryDiscoveryEvent repoEvent) {
        DiscoveryAction action = repoEvent.action();
        GHRepository repo = repoEvent.repository();
        String repoFullName = repo.getFullName();
        String orgName = toOrganizationName(repoFullName);

        Log.debugf("[%s] repositoryDiscovered (%s): %s", ME, repoEvent.action(), repoFullName);
        long installationId = repoEvent.installationId();

        // We only read configuration files from repositories in the configured
        // organization
        if (action.repository() && orgName.equals(mgrBotConfig.home().organization())) {
            final String taskGroup = repoNametoTaskGroup(repoFullName);

            if (action.added()) {
                ScopedQueryContext qc = new ScopedQueryContext(ctx, installationId, repo)
                        .withExisting(repoEvent.github());

                updateQueue.queue(taskGroup, () -> readProjectConfig(taskGroup, qc));

                if (!repoEvent.bootstrap()) {
                    // If this is after bootstrap phase, also queue reconcile event
                    updateQueue.queueReconciliation(taskGroup, () -> reconcile(taskGroup));
                }

                // Register watcher to monitor for org config changes
                fileWatcher.watchFile(taskGroup,
                        installationId, repoFullName, ProjectConfig.PATH,
                        (fileUpdate) -> processFileUpdate(taskGroup, fileUpdate));

            } else if (action.removed()) {
                // Remove any state associated with the repository (all watcher cleanup handled
                // separately)
                var state = taskGroupToState.remove(taskGroup);
                if (state != null && state != EMPTY) {
                    for (var group : state.projectConfig().teamMembership()) {
                        var source = group.source();
                        if (source != null && !source.isEmpty()) {
                            unwatchRepoSource(state, source);
                        }
                    }
                }
            }
        }
    }

    /** All repositories have been discovered: Organization and project config have been detected */
    protected void bootstrapComplete(@Observes @Priority(value = RdePriority.APP_DISCOVERY) BootstrapDiscoveryEvent event) {
        if (taskState.shouldRun(ME, Duration.ofHours(6))) {
            queueReconciliation();
        } else {
            Log.debugf("[%s] bootstrapComplete: Skip eager team sync", ME);
        }
    }

    protected void queueReconciliation() {
        // Add this to the queue so it occurs after project configurations are read
        updateQueue.queue(ME, () -> {
            Log.debugf("[%s] queueReconciliation: Reconcile all project configurations", ME);
            for (String taskGroup : taskGroupToState.keySet()) {
                updateQueue.queueReconciliation(taskGroup, () -> reconcile(taskGroup));
            }
        });
    }

    /**
     * Process inbound membership changes
     * Queue a reconcilation. No-need to re-read config
     */
    protected void processMembershipUpdate(String taskGroup, MembershipUpdate update) {
        Log.debugf("[%s] processMembershipUpdate: %s", ME, update);
        ProjectConfigState newState = taskGroupToState.get(taskGroup);
        if (newState == null) {
            Log.warnf("[%s] processMembershipUpdate: no state for %s", ME, taskGroup);
            return;
        }

        if (newState == EMPTY) { // lazy discovery
            Log.debugf("[%s] processMembershipUpdate: empty state for %s", ME, taskGroup);
            ScopedQueryContext qc = new ScopedQueryContext(ctx, update.installationId(), update.orgName());
            readProjectConfig(taskGroup, qc);
        } else {
            // queue reconcile action
            updateQueue.queueReconciliation(ME, () -> reconcile(taskGroup));
        }
    }

    @Override
    protected void processRepoSourceUpdate(String taskGroup, RepoSource repoSource) {
        // queue reconcile action: deal with bursty config updates
        updateQueue.queue(taskGroup, () -> {
            ProjectConfigState configState = taskGroupToState.get(taskGroup);
            if (configState == null) {
                Log.warnf("[%s] processRepoSourceUpdate: no state for %s", ME, taskGroup);
                return;
            }
            Log.debugf("[%s] processRepoSourceUpdate: %s %s", ME, taskGroup, repoSource);

            ProjectConfig config = configState.projectConfig();
            List<GroupMapping> mappings = config.teamMembership().stream()
                    .filter(mapping -> repoSource.equals(mapping.source()))
                    .toList();

            for (var mapping : mappings) {
                processGroupMapping(configState, mapping);
            }
        });
    }

    /**
     * Read project configuration from repository.
     * Called for file update events
     */
    protected void processFileUpdate(String taskGroup, FileUpdate fileUpdate) {
        if (fileUpdate.updateType() == FileUpdateType.REMOVED) {
            String repoFullName = fileUpdate.repository().getFullName();
            Log.debugf("[%s] processFileUpdate: %s %s deleted", ME, taskGroup, repoFullName);
            taskGroupToState.remove(taskGroup);
            return;
        }
        ScopedQueryContext qc = new ScopedQueryContext(ctx, fileUpdate.installationId(), fileUpdate.repository());
        readProjectConfig(taskGroup, qc);
        updateQueue.queueReconciliation(ME, () -> reconcile(taskGroup));
    }

    /**
     * Read organization configuration from repository.
     * Called by repositoryDiscovered, on file events, and periodic sync
     */
    protected void readProjectConfig(String taskGroup, ScopedQueryContext qc) {
        ProjectConfigState oldState = taskGroupToState.get(taskGroup);
        String repoFullName = taskGroupToRepo(taskGroup);

        // The repository containing the (added/modified) file must be present in the
        // query context
        GHRepository repo = qc.getRepository(repoFullName);
        if (repo == null) {
            Log.warnf("[%s] readProjectConfig %s: repository not set in QueryContext", ME, taskGroup);
            return;
        }

        GHContent content = qc.readSourceFile(repo, ProjectConfig.PATH);
        if (qc.hasErrors()) {
            qc.logAndSendContextErrors("[%s] unable to read project config %s".formatted(ME, taskGroup));
            return;
        } else if (content == null) {
            // Normal for lazy-discovery; this will be re-added if/when the file appears
            Log.debugf("[%s] readProjectConfig %s: no %s in %s", ME, taskGroup, ProjectConfig.PATH, repo.getFullName());
            taskGroupToState.remove(taskGroup);
            return;
        }

        ProjectConfig projectConfig = qc.readYamlContent(content, ProjectConfig.class);
        if (projectConfig == null || qc.hasErrors()) {
            ctx.sendEmail(me(), "haus-manager project configuration could not be read", """
                    Source file %s could not be read (or parsed) from %s.

                    %s
                    """.formatted(ProjectConfig.PATH,
                    repo.getFullName(),
                    qc.bundleExceptions()),
                    qc.getErrorAddresses(oldState == null ? null : oldState.emailNotifications()));
            return;
        }
        Log.debugf("[%s] readProjectConfig %s: found %s in %s", ME, taskGroup, ProjectConfig.PATH, repo.getFullName());

        ProjectConfigState newState = new ProjectConfigState(taskGroup,
                () -> {
                    ScopedQueryContext taskQc = new ScopedQueryContext(ctx, qc.getInstallationId(), repo);
                    readProjectConfig(taskGroup, taskQc);
                },
                repo.getFullName(), qc.getInstallationId(), projectConfig);

        if (oldState != null) {
            if (oldState.sourceTeamHasChanged(newState)) {
                Log.debugf("[%s] readProjectConfig %s: remove old state source -- %s", ME, taskGroup,
                        oldState.sourceTeam());
                membershipEvents.unwatch(MembershipUpdateType.TEAM, oldState.sourceTeam());
            }
            if (oldState.healthCollectionHasChanged(newState)) {
                for (var callback : callbacks.values()) {
                    callback.onProjectConfigUpdate(callback.getTaskGroup(newState.repoFullName()), newState);
                }
            }
            if (oldState != EMPTY) {
                Set<RepoSource> oldSources = oldState.projectConfig().teamMembership().stream()
                        .map(GroupMapping::source)
                        .filter(x -> x != null && !x.isEmpty())
                        .collect(Collectors.toSet());

                for (GroupMapping mapping : newState.projectConfig().teamMembership()) {
                    if (mapping != null && mapping.source() != null && !mapping.source().isEmpty()) {
                        oldSources.remove(mapping.source());
                    }
                }
                for (RepoSource oldSource : oldSources) {
                    unwatchRepoSource(newState, oldSource);
                }

                Set<String> removedTeams = oldState.targetTeams();
                removedTeams.removeAll(newState.targetTeams());
                teamConflictResolver.releaseProjectTeams(newState, removedTeams);
            }
        }

        teamConflictResolver.registerProjectTeams(newState);
        // Add the source file to the state
        taskGroupToState.put(taskGroup, newState);
    }

    /**
     * Reconcile team membership with project/org configuration
     * Review collected configuration and perform required actions
     *
     * @param taskGroup
     */
    public void reconcile(String taskGroup) {
        // Always fetch latest state (in case of changes / skips)
        ProjectConfigState state = taskGroupToState.get(taskGroup);
        ProjectConfig projectConfig = state.projectConfig();
        Log.debugf("[%s] %s: team membership sync; %s", ME, taskGroup, projectConfig);

        boolean nothingToDo = projectConfig.collaboratorSync() == null && projectConfig.teamMembership().isEmpty();
        if (!projectConfig.enabled() || nothingToDo) {
            Log.infof("[%s] %s: configuration disabled or nothing to do; skipping %s", ME, taskGroup,
                    projectConfig);
            return;
        }

        // Sync collaborators
        doSyncCollaborators(state, projectConfig);

        // Sync team memberships
        for (GroupMapping groupMapping : projectConfig.teamMembership()) {
            if (groupMapping == null || !groupMapping.performSync()) {
                Log.debugf("[%s] %s: missing or empty group mapping: %s", ME, taskGroup, groupMapping);
                continue;
            }
            // Register watcher for the source file so we get notified when it changes
            watchRepoSource(state, groupMapping.source());
            processGroupMapping(state, groupMapping);
        }

        Log.debugf("[%s] %s: team membership sync complete; %s", ME, taskGroup, state.projectConfig());
    }

    private void doSyncCollaborators(ProjectConfigState state, ProjectConfig projectConfig) {
        CollaboratorSync teamAccess = projectConfig.collaboratorSync();
        String sourceTeamName = teamAccess == null ? null : teamAccess.sourceTeam();
        if (sourceTeamName == null) {
            Log.warnf("[%s] %s: No source team configured; skipping team sync", ME, state.taskGroup());
            return;
        }

        String repoFullName = state.repoFullName();
        boolean isDryRun = projectConfig.dryRun() || ctx.isDryRun();

        ScopedQueryContext projectQc = new ScopedQueryContext(ctx, state.installationId(), repoFullName);

        // Find the repository where the configuration file is located
        GHRepository repo = projectQc.getRepository(repoFullName);

        // Include any hard-coded collaborators from the configuration
        Set<String> sourceLogins = new HashSet<>(teamAccess.includeUsers());

        // Find members of the source team
        ScopedQueryContext teamQc = projectQc.forOrganization(sourceTeamName, isDryRun);
        if (teamQc == null) {
            Log.warnf("[%s] %s: No installation associated with team %s", ME, state.taskGroup(), sourceTeamName);
        } else {
            // Get team members
            Set<String> teamLogins = teamService.getTeamLogins(teamQc, sourceTeamName);
            if (teamLogins == null) {
                Log.warnf("[%s] %s: team was not found %s", ME, state.taskGroup(), sourceTeamName);
            } else {
                sourceLogins.addAll(teamLogins);

                // Watch for changes on the source team
                membershipEvents.watchMembers(state.taskGroup(),
                        teamQc.getInstallationId(),
                        MembershipUpdateType.TEAM,
                        sourceTeamName,
                        (update) -> processMembershipUpdate(state.taskGroup(), update));
            }
        }

        // We got nobody. Skip the sync.
        if (sourceLogins.isEmpty()) {
            Log.warnf("[%s] %s: No source logins found for %s; skipping team sync", ME, state.taskGroup(),
                    sourceTeamName);
            return;
        }

        GHOrganization.RepositoryRole role = toRole("doSyncCollaborators", teamAccess.role(),
                projectConfig.emailNotifications(), teamAccess);

        // Add configured logins as outside collaborators on the repository that
        // contains the configuration file.
        teamService.syncCollaborators(projectQc, repo, role,
                sourceLogins, teamAccess.ignoreUsers(),
                isDryRun, projectConfig.emailNotifications());

        taskGroupToState.put(state.taskGroup(), state);

        // Note: taskGroup is tied to the repo where the config file lives
        // Watch for collaborator changes on the repository
        membershipEvents.watchMembers(state.taskGroup(),
                projectQc.getInstallationId(),
                MembershipUpdateType.COLLABORATOR,
                repoFullName,
                (update) -> processMembershipUpdate(state.taskGroup(), update));
    }

    protected String me() {
        return ME;
    }

    /**
     * Hard-reset of the organization manager.
     * This is useful for testing.
     */
    protected void reset() {
        fileWatcher.unwatchAll(ME);
        membershipEvents.unwatchAll(ME);
        taskGroupToState.clear();
    }

    public record ProjectConfigState(
            String taskGroup,
            Runnable refresh,
            String repoFullName,
            long installationId,
            ProjectConfig projectConfig,
            Set<RepoSource> sources,
            Set<String> blockedTeams) implements ConfigState {

        public ProjectConfigState(String taskGroup, Runnable refresh, String repoFullName, long installationId,
                ProjectConfig projectConfig) {
            this(taskGroup, refresh, repoFullName, installationId, projectConfig, new HashSet<>(), new HashSet<>());
        }

        public Set<String> targetTeams() {
            return projectConfig == null
                    ? Set.of()
                    : projectConfig.teamMembership().stream()
                            .flatMap(gm -> gm.pushMembers().values().stream())
                            .flatMap(pts -> pts.teams().stream())
                            .collect(Collectors.toSet());
        }

        public boolean add(RepoSource source) {
            return sources.add(source);
        }

        public boolean remove(RepoSource source) {
            return sources.remove(source);
        }

        public boolean addBlockedTeam(String teamName) {
            return blockedTeams.add(teamName);
        }

        public Set<String> blockedTeams() {
            return blockedTeams;
        }

        // ProjectConfig is only unset when using an empty placeholder
        public String sourceTeam() {
            if (this == EMPTY) {
                return null;
            }
            CollaboratorSync myAccess = projectConfig.collaboratorSync();
            return myAccess != null ? myAccess.sourceTeam() : null;
        }

        public boolean healthCollectionHasChanged(ProjectConfigState newState) {
            boolean oldEnabled = this != EMPTY && this.healthCollectionEnabled();
            boolean newEnabled = newState != EMPTY && newState.healthCollectionEnabled();

            // Health collection toggled on or off
            if (oldEnabled != newEnabled) {
                return newEnabled; // Only trigger collection if newly enabled
            }

            // Both enabled - check if health config changed
            if (oldEnabled && newEnabled) {
                return !Objects.equals(
                        this.projectConfig.projectHealth(),
                        newState.projectConfig.projectHealth());
            }

            // Neither enabled
            return false;
        }

        public boolean sourceTeamHasChanged(ProjectConfigState newState) {
            if (this == EMPTY) {
                return false; // nothing to clean up
            }
            CollaboratorSync myAccess = projectConfig.collaboratorSync();
            if (myAccess == null || myAccess.sourceTeam() == null) {
                return false; // nothing to clean up
            }
            CollaboratorSync newAccess = newState.projectConfig.collaboratorSync();
            return newAccess == null || !Objects.equals(myAccess.sourceTeam(), newAccess.sourceTeam());
        }

        public boolean healthCollectionEnabled() {
            if (this == EMPTY) {
                return false; // not configured yet (wait)
            }
            ProjectConfig config = projectConfig();
            return config != null
                    && config.projectHealth() != null
                    && !config.projectHealth().organizationRepositories().isEmpty();
        }

        public String[] errors() {
            return this == EMPTY ? null : projectConfig().emailNotifications().errors();
        }

        @Override
        public EmailNotification emailNotifications() {
            return this == EMPTY ? null : projectConfig().emailNotifications();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ProjectConfigState other = (ProjectConfigState) o;
            return installationId == other.installationId &&
                    repoFullName.equals(other.repoFullName) &&
                    taskGroup.equals(other.taskGroup);
        }

        @Override
        public int hashCode() {
            return Objects.hash(installationId, repoFullName, taskGroup);
        }
    }
}
