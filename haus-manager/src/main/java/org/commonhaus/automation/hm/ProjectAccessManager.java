package org.commonhaus.automation.hm;

import static org.commonhaus.automation.github.context.GitHubQueryContext.toOrganizationName;
import static org.commonhaus.automation.github.context.GitHubTeamService.refreshCollaborators;
import static org.commonhaus.automation.github.context.GitHubTeamService.refreshTeam;

import java.time.Duration;
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
import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent.RdePriority;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdate;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdateType;
import org.commonhaus.automation.github.watchers.MembershipWatcher.MembershipUpdate;
import org.commonhaus.automation.github.watchers.MembershipWatcher.MembershipUpdateType;
import org.commonhaus.automation.hm.config.GroupMapping;
import org.commonhaus.automation.hm.config.ProjectConfig;
import org.commonhaus.automation.hm.config.ProjectConfig.CollaboratorSync;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class ProjectAccessManager extends GroupCoordinator {
    static final String ME = "🌳-project";
    static final ProjectConfigState EMPTY = new ProjectConfigState(null, null, 0, null);

    // flat map of tracked resources to the task group that manages them
    final Map<String, String> resourceToTaskGroup = new ConcurrentHashMap<>();

    // flat map of task group to its current state
    final Map<String, ProjectConfigState> taskGroupToState = new ConcurrentHashMap<>();

    void startup(@Observes StartupEvent startup) {
        RouteSupplier.registerSupplier("Project access refreshed", () -> lastRun);
    }

    private static String repoNametoTaskGroup(String repoFullName) {
        return "%s-%s".formatted(ME, repoFullName);
    }

    private static String taskGroupToRepo(String taskGroup) {
        return taskGroup.substring(ME.length() + 1);
    }

    private static String teamToResource(String teamFullName) {
        return "team-" + teamFullName;
    }

    private static String repoToResource(String repoFullName) {
        return "repo-" + repoFullName;
    }

    public static String resourceToFullName(String resource) {
        return resource.substring(5);
    }

    /**
     * Periodically refresh/re-synchronize team access lists.
     */
    // Quartz cron expression: s m h dom mon dow year(optional)
    @Scheduled(cron = "${automation.hausManager.cron.projects:0 47 4 */3 * ?}")
    public void scheduledRefresh() {
        try {
            Log.infof("[%s] ⏰ Scheduled: begin refresh access lists", ME);
            refreshAccessLists(false);
        } catch (Throwable t) {
            ctx.logAndSendEmail(ME, "⏰ 🌳 Error running scheduled access list refresh", t);
        }
    }

    /**
     * Allow manual trigger from admin endpoint
     */
    public void refreshAccessLists(boolean userTriggered) {
        if (!userTriggered && !taskState.shouldRun(ME, Duration.ofHours(6))) {
            Log.infof("[%s]: skip scheduled project access update (last run: %s)", ME, lastRun);
            return;
        }
        recordRun();

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
        for (var entry : taskGroupToState.entrySet()) {
            String taskGroup = entry.getKey();
            String repoFullName = taskGroupToRepo(taskGroup);
            ScopedQueryContext qc = ctx.getOrgScopedQueryContext(repoFullName);
            if (qc == null) {
                Log.warnf("[%s] %s: no org context for %s", ME, taskGroup, repoFullName);
                continue;
            }
            readProjectConfig(taskGroup, qc);
        }
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

        // We only read configuration files from repositories in the configured organization
        if (action.repository() && orgName.equals(mgrBotConfig.home().organization())) {
            final String taskGroup = repoNametoTaskGroup(repoFullName);

            if (action.added()) {
                ScopedQueryContext qc = new ScopedQueryContext(ctx, installationId, repo)
                        .withExisting(repoEvent.github());

                if (taskState.shouldRun(ME, Duration.ofHours(6))) {
                    updateQueue.queue(taskGroup, () -> readProjectConfig(taskGroup, qc));
                } else {
                    Log.debug("Skip eager project discovery (ran recently); lazy discovery on updates");

                    // Leave an outpost so we know it is interesting
                    resourceToTaskGroup.put(repoToResource(repoFullName), taskGroup);
                    taskGroupToState.put(taskGroup, EMPTY);
                }

                // Register watcher to monitor for org config changes
                // GHRepository is backed by a cached connection that can expire
                // use the GitHub object from the event to ensure a fresh connection
                fileWatcher.watchFile(taskGroup,
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
            // queue reconcile action: deal with bursty config updates
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
        recordRun();

        if (fileUpdate.updateType() == FileUpdateType.REMOVED) {
            String repoFullName = fileUpdate.repository().getFullName();
            Log.debugf("[%s] processFileUpdate: %s %s deleted", ME, taskGroup, repoFullName);
            taskGroupToState.remove(taskGroup);
            return;
        }
        ScopedQueryContext qc = new ScopedQueryContext(ctx, fileUpdate.installationId(), fileUpdate.repository());
        readProjectConfig(taskGroup, qc);
    }

    /**
     * Read organization configuration from repository.
     * Called by repositoryDiscovered, on file events, and periodic sync
     */
    protected void readProjectConfig(String taskGroup, ScopedQueryContext qc) {
        ProjectConfigState oldState = taskGroupToState.get(taskGroup);
        String repoFullName = taskGroupToRepo(taskGroup);

        // The repository containing the (added/modified) file must be present in the query context
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
                repo.getFullName(), qc.getInstallationId(), projectConfig);

        if (oldState != null && oldState.sourceTeamHasChanged(newState)) {
            Log.debugf("[%s] readProjectConfig %s: remove old state source -- %s", ME, taskGroup, oldState.sourceTeam());
            resourceToTaskGroup.remove(repoToResource(oldState.sourceTeam()));
            membershipEvents.unwatch(MembershipUpdateType.TEAM, oldState.sourceTeam());
        }

        // Clean up GroupMapping source file watchers for changed/removed mappings
        if (oldState != null && oldState != EMPTY) {
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
        }

        taskGroupToState.put(taskGroup, newState);

        // queue reconcile action: deal with bursty config updates
        updateQueue.queueReconciliation(taskGroup, () -> reconcile(taskGroup));
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
        Log.debugf("[%s] %s: team membership sync; %s", ME, taskGroup, state.projectConfig());

        ProjectConfig projectConfig = state.projectConfig();
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
            Log.warnf("[%s] %s: No source logins found for %s; skipping team sync", ME, state.taskGroup(), sourceTeamName);
            return;
        }

        GHOrganization.RepositoryRole role = toRole("doSyncCollaborators", teamAccess.role(),
                projectConfig.emailNotifications(), teamAccess);

        // Add configured logins as outside collaborators on the repository that contains the configuration file.
        teamService.syncCollaborators(projectQc, repo, role,
                sourceLogins, teamAccess.ignoreUsers(),
                isDryRun, projectConfig.emailNotifications());

        // Update/keep references: sourceTeam -> taskGroup; taskGroup -> state
        resourceToTaskGroup.put(sourceTeamName, state.taskGroup());
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
        resourceToTaskGroup.clear();
        taskGroupToState.clear();
    }

    record ProjectConfigState(
            String taskGroup,
            String repoFullName,
            long installationId,
            ProjectConfig projectConfig,
            Set<RepoSource> sources) implements ConfigState {

        public ProjectConfigState(String taskGroup, String repoFullName, long installationId, ProjectConfig projectConfig) {
            this(taskGroup, repoFullName, installationId, projectConfig, new HashSet<>());
        }

        public boolean add(RepoSource source) {
            return sources.add(source);
        }

        public boolean remove(RepoSource source) {
            return sources.remove(source);
        }

        // ProjectConfig is only unset when using an empty placeholder
        public String sourceTeam() {
            if (this == EMPTY) {
                return null;
            }
            CollaboratorSync myAccess = projectConfig.collaboratorSync();
            return myAccess != null ? myAccess.sourceTeam() : null;
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

        public String[] errors() {
            return this == EMPTY ? null : projectConfig().emailNotifications().errors();
        }

        @Override
        public String repoName() {
            return repoFullName;
        }

        @Override
        public EmailNotification emailNotifications() {
            return this == EMPTY ? null : projectConfig().emailNotifications();
        }
    }
}
