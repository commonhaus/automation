package org.commonhaus.automation.hk;

import static org.commonhaus.automation.github.context.GitHubQueryContext.toOrganizationName;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.config.RepoSource;
import org.commonhaus.automation.config.RouteSupplier;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.github.context.GitHubTeamService;
import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent.RdePriority;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.github.watchers.FileWatcher;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdate;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdateType;
import org.commonhaus.automation.hk.UserLoginVerifier.LoginChangeEvent;
import org.commonhaus.automation.hk.config.AdminBotConfig;
import org.commonhaus.automation.hk.config.AliasManagementConfig;
import org.commonhaus.automation.hk.config.HausKeeperConfig;
import org.commonhaus.automation.hk.config.ProjectAliasMapping;
import org.commonhaus.automation.hk.config.ProjectAliasMapping.UserAliasList;
import org.commonhaus.automation.hk.config.ProjectSourceConfig;
import org.commonhaus.automation.hk.data.CommonhausUser;
import org.commonhaus.automation.hk.github.AppContextService;
import org.commonhaus.automation.hk.github.CommonhausDatastore;
import org.commonhaus.automation.hk.github.DatastoreEvent.UpdateEvent;
import org.commonhaus.automation.queue.PeriodicUpdateQueue;
import org.commonhaus.automation.queue.ScheduledService;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class ProjectAliasManager extends ScheduledService {
    private static final String ME = "📫-aliases";
    private static final AliasConfigState EMPTY = new AliasConfigState(null, null, null, 0, null);

    @Inject
    ActiveHausKeeperConfig hkConfig;

    @Inject
    AppContextService ctx;

    @Inject
    AdminBotConfig adminBotConfig;

    @Inject
    CommonhausDatastore datastore;

    @Inject
    FileWatcher fileWatcher;

    @Inject
    PeriodicUpdateQueue updateQueue;

    @Inject
    GitHubTeamService teamService;

    // flat map of task group to its current state
    final Map<String, AliasConfigState> taskGroupToState = new ConcurrentHashMap<>();
    final AtomicReference<Map<String, ProjectSourceConfig>> knownProjectDomains = new AtomicReference<>();

    void startup(@Observes @Priority(value = RdePriority.APP_DISCOVERY) StartupEvent startup) {
        RouteSupplier.registerSupplier("Project aliases refreshed", () -> lastRun);
        hkConfig.notifyOnUpdate(ME, () -> {
            Log.infof("HausKeeper project aliases config updated: %s", hkConfig.getProjectAliasesConfig());
            updateDomainMap();
        });
    }

    /**
     * Periodically refresh/re-synchronize team aliases.
     */
    // Quartz cron expression: s m h dom mon dow year(optional)
    @Scheduled(cron = "${automation.hausKeeper.cron.projectAliases:0 47 4 */3 * ?}")
    public void scheduledRefresh() {
        try {
            Log.infof("[%s] ⏰ Scheduled: begin refresh project aliases", ME);
            refreshProjectAliases(false);
        } catch (Throwable t) {
            ctx.logAndSendEmail(ME, "📫 ⏰ Error running scheduled refresh of project aliases", t);
        }
    }

    /**
     * Allow manual trigger by admin endpoint
     */
    public void refreshProjectAliases(boolean userTriggered) {
        if (!userTriggered && !taskState.shouldRun(ME, Duration.ofHours(6))) {
            Log.infof("[%s]: skip scheduled project refresh (last run: %s)", ME, lastRun);
            return;
        }
        recordRun();
        for (var entry : taskGroupToState.entrySet()) {
            String repoFullName = taskGroupToRepo(entry.getKey());
            ScopedQueryContext qc = ctx.getScopedQueryContext(repoFullName);
            qc.getRepository(repoFullName);
            readProjectConfig(entry.getKey(), qc, true);
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
        if (action.repository() && orgName.equals(adminBotConfig.home().organization())) {
            final String taskGroup = repoToTaskGroup(repoFullName);

            if (action.added()) {
                ScopedQueryContext qc = new ScopedQueryContext(ctx, installationId, repo)
                        .withExisting(repoEvent.github());

                if (!hkConfig.isReady()) {
                    updateQueue.queue(taskGroup, () -> repositoryDiscovered(repoEvent));
                } else if (taskState.shouldRun(ME, Duration.ofHours(6))) {
                    updateQueue.queue(taskGroup, () -> readProjectConfig(taskGroup, qc, true));
                } else {
                    Log.debug("Skip eager project discovery (ran recently); lazy discovery on updates/cron");
                    taskGroupToState.put(taskGroup, EMPTY);
                }
            } else {
                taskGroupToState.remove(taskGroup);
            }
        }
    }

    protected void processFileUpdate(String taskGroup, FileUpdate fileUpdate) {
        if (fileUpdate.updateType() == FileUpdateType.REMOVED) {
            String repoFullName = fileUpdate.repository().getFullName();
            Log.debugf("[%s] processFileUpdate: %s deleted", taskGroup, repoFullName);
            taskGroupToState.put(taskGroup, EMPTY);
            return;
        }

        ScopedQueryContext qc = new ScopedQueryContext(ctx, fileUpdate.installationId(), fileUpdate.repository());
        readProjectConfig(taskGroup, qc, true);
    }

    protected void readProjectConfig(String taskGroup, ScopedQueryContext qc, boolean queueReconciliation) {
        AliasManagementConfig aliasMgmtConfig = hkConfig.getProjectAliasesConfig();
        if (aliasMgmtConfig.isDisabled()) {
            Log.debugf("%s: project aliases sync disabled", taskGroup);
            return;
        }

        // The repository containing the (added/modified) file must be present in the query context
        String repoFullName = taskGroupToRepo(taskGroup);
        GHRepository repo = qc.getRepository(repoFullName);
        if (repo == null || qc.hasErrors()) {
            Log.warnf("%s readProjectConfig: repository not set in QueryContext: %s", taskGroup, qc.bundleExceptions());
            return;
        }

        String projectName = aliasMgmtConfig.toProjectName(repoFullName);
        ProjectSourceConfig projectConfig = getCentralProjectConfig(projectName);
        if (projectConfig == null) {
            Log.debugf("%s readProjectConfig: Repository %s does not map to a known project (%s)", taskGroup,
                    repoFullName, projectName);
            taskGroupToState.remove(taskGroup);
            return;
        }

        GHContent content = qc.readSourceFile(repo, ProjectAliasMapping.CONFIG_FILE);
        if (content == null || qc.hasErrors()) {
            // Normal
            Log.debugf("%s readProjectConfig: no %s in %s", taskGroup,
                    ProjectAliasMapping.CONFIG_FILE, repoFullName);
            return;
        }

        ProjectAliasMapping aliasConfig = qc.readYamlContent(content, ProjectAliasMapping.class);
        if (aliasConfig == null || qc.hasErrors()) {
            ctx.sendEmail(ME, "haus-keeper project mail configuration could not be read", """
                    Source file %s could not be read (or parsed) from %s.

                    %s
                    """.formatted(ProjectAliasMapping.CONFIG_FILE,
                    repo.getFullName(),
                    qc.bundleExceptions()),
                    qc.getErrorAddresses());
            return;
        }
        Log.debugf("%s readProjectConfig: found %s in %s", taskGroup, ProjectAliasMapping.CONFIG_FILE,
                repo.getFullName());

        AliasConfigState newState = new AliasConfigState(taskGroup,
                projectName, repo.getFullName(),
                qc.getInstallationId(), aliasConfig);

        taskGroupToState.put(taskGroup, newState);

        // queue reconcile action: deal with bursty config updates
        if (queueReconciliation) {
            updateQueue.queueReconciliation(taskGroup, () -> reconcile(taskGroup));
        }
    }

    public void reconcile(String taskGroup) {
        // Always fetch latest state (in case of changes / skips)
        AliasConfigState state = taskGroupToState.get(taskGroup);
        if (state == null || state.projectConfig() == null) {
            Log.debugf("%s: no state or project config to reconcile", taskGroup);
            return;
        }

        Log.debugf("%s: aliases sync; %s", taskGroup, state.projectConfig());

        ProjectSourceConfig centralProjectConfig = getCentralProjectConfig(state.projectName());
        if (centralProjectConfig == null) {
            Log.debugf("%s: project config not found for %s", taskGroup, state.projectName());
            taskGroupToState.remove(taskGroup);
            return;
        }

        ScopedQueryContext qc = new ScopedQueryContext(ctx, state.installationId(), state.repoFullName());
        ProjectAliasMapping projectAliasConfig = state.projectConfig();

        String definitiveMailDomain = centralProjectConfig.mailDomain();
        String projectDomain = projectAliasConfig.domain();
        if (!Objects.equals(definitiveMailDomain, projectDomain)) {
            Log.debugf("%s: project domain mismatch; %s != %s", taskGroup, projectDomain, definitiveMailDomain);
            String title = "HausKeeper: project alias domain mismatch";
            String message = """
                    Project alias domain mismatch for %s

                    Project alias domain: %s
                    Central project alias domain: %s
                    """.formatted(state.repoFullName(), projectDomain, definitiveMailDomain);
            ctx.sendEmail(ME, title, message,
                    qc.getErrorAddresses(projectAliasConfig.emailNotifications()));
            qc.createItem(EventType.issue, title, message, null);
            return;
        }

        if (projectAliasConfig.userMapping() == null || projectAliasConfig.userMapping().isEmpty()) {
            Log.debugf("%s: no user mappings defined in project alias config", taskGroup);
            return;
        }

        // For each user in the mapping, ensure their aliases exist and are up to date
        for (UserAliasList userAliases : projectAliasConfig.userMapping()) {
            String login = userAliases.login();
            if (userAliases.aliases().isEmpty()) {
                Log.debugf("%s: no aliases defined for login %s", taskGroup, login);
                continue;
            }
            GHUser ghUser = login == null ? null : qc.getUser(login);

            // If anything about the user alias is wrong, send an email and skip it
            if (qc.hasErrors()) {
                qc.logAndSendEmail("Error fetching user from GitHub",
                        "%s: error fetching user %s".formatted(taskGroup, login),
                        qc.bundleExceptions(),
                        projectAliasConfig.emailNotifications());
                return; // stop processing. We will try again later (e.g the next cron run or after a fix)
            } else if (ghUser == null || !userAliases.isValid(projectDomain)) {
                Log.debugf("%s: invalid aliases for login %s (%s)", taskGroup, userAliases, ghUser);
                String title = "Invalid alias defined";
                String message = """

                        Login: %s
                        Aliases: %s

                        Aliases should be fully qualified email addresses ending with @%s.

                        Project config: %s
                        """.formatted(state.repoFullName(),
                        userAliases.login(),
                        userAliases.aliases(),
                        projectDomain,
                        projectAliasConfig);
                ctx.sendEmail(ME, title, message,
                        qc.getErrorAddresses(projectAliasConfig.emailNotifications()));
                qc.createItem(EventType.issue, title, message, null);
                continue;
            }

            try {
                // Create a new user object if it does not exist
                CommonhausUser user = datastore.getCommonhausUser(login, ghUser.getId(), false, true);

                if (user.aliasesMatch(state.projectName(), projectDomain, userAliases.aliases())) {
                    Log.debugf("%s: user %s already has aliases %s", taskGroup, login, userAliases.aliases());
                    continue; // skip
                }

                // Make changes to the user object within a retryable unit:
                // add configured aliases and make sure project was added to user
                datastore.setCommonhausUser(new UpdateEvent(user,
                        (c, u) -> {
                            u.addProject(state.projectName());
                            u.services().forwardEmail().addAliases(userAliases.aliases());
                        },
                        "Update user aliases for " + projectDomain,
                        true, true));
            } catch (Exception e) {
                qc.addException(e);
            }
            if (qc.hasErrors()) {
                qc.logAndSendEmail("Error adding project email aliases",
                        "%s: error updating user %s".formatted(taskGroup, login),
                        qc.bundleExceptions(),
                        projectAliasConfig.emailNotifications());
                return; // stop processing. We will try again later (e.g the next cron run or after a fix)
            } else {
                Log.debugf("%s: updated user %s with aliases %s", taskGroup, login, userAliases.aliases());
            }
        }
        Log.debugf("%s: project alias sync complete; %s", taskGroup, state.projectConfig());
    }

    void notifyUserProjects(@Observes LoginChangeEvent loginChangeEvent) {
        AliasManagementConfig aliasMgmtConfig = hkConfig.getProjectAliasesConfig();
        if (aliasMgmtConfig.isDisabled()) {
            Log.debugf("[%s] %s: project aliases sync disabled", ME, loginChangeEvent);
            return;
        }

        // This event is fired when a user changes their login
        // Any projects that the user is a member of should be notified
        // so configurations can be modified.
        for (var project : loginChangeEvent.projects()) {
            for (var entry : taskGroupToState.entrySet()) {
                String taskGroup = entry.getKey();
                String repoFullName = taskGroupToRepo(taskGroup);
                String projectName = aliasMgmtConfig.toProjectName(repoFullName);
                if (projectName.equals(project)) {
                    var state = entry.getValue();
                    if (state == EMPTY) {
                        // Read the configuration but don't queue reconciliation
                        updateQueue.queue(taskGroup, () -> {
                            ScopedQueryContext qc = ctx.getScopedQueryContext(repoFullName);
                            readProjectConfig(taskGroup, qc, false);
                            // Once config is read, directly notify
                            notifyProject(taskGroup, loginChangeEvent);
                        });
                    } else {
                        notifyProject(taskGroup, loginChangeEvent);
                    }
                }
            }
        }
    }

    void notifyProject(String taskGroup, LoginChangeEvent loginChangeEvent) {
        AliasConfigState state = taskGroupToState.get(taskGroup);
        if (state == null) {
            // It was empty. We read the config, and there was none; removed
            Log.debugf("%s: notifyProject: no state", taskGroup);
            return;
        }
        ScopedQueryContext qc = new ScopedQueryContext(ctx, state.installationId(), state.repoFullName());
        Log.debugf("%s notifyProjectLeaders: notifying project %s", taskGroup, state.projectName());
        String title = "haus-keeper: user login changed";
        String message = """
                User %s has changed their login%s.
                Please check the project alias configuration in %s.
                """.formatted(
                loginChangeEvent.oldLogin(),
                loginChangeEvent.newLogin().map(l -> " to " + l).orElse(""),
                state.repoFullName());

        ctx.sendEmail(ME, title, message,
                qc.getErrorAddresses(state.projectConfig().emailNotifications()));
        qc.createItem(EventType.issue, title, message, null);
    }

    private ProjectSourceConfig getCentralProjectConfig(String projectName) {
        Map<String, ProjectSourceConfig> map = knownProjectDomains.get();
        if (map == null) {
            map = updateDomainMap();
        }
        ProjectSourceConfig config = map.get(projectName);
        if (config == null) {
            Log.warnf("%s getCentralProjectConfig: no project list found for %s", ME, projectName);
        }
        return config;
    }

    private Map<String, ProjectSourceConfig> updateDomainMap() {
        Map<String, ProjectSourceConfig> map = Map.of();
        knownProjectDomains.set(map); // ensure non-null

        AliasManagementConfig aliasMgmtConfig = hkConfig.getProjectAliasesConfig();
        if (aliasMgmtConfig.isDisabled()) {
            return map;
        }
        RepoSource projectList = aliasMgmtConfig.projectList();
        String repoFullName = projectList.repository();
        ScopedQueryContext qc = ctx.getScopedQueryContext(repoFullName);
        GHRepository repo = qc == null ? null : qc.getRepository(repoFullName);
        if (qc == null || repo == null) {
            Log.warnf("[%s] updateDomainMap: no query context for %s", ME, projectList);
            return map;
        }
        GHContent content = qc.readSourceFile(repo, projectList.filePath());
        if (content == null || qc.hasErrors()) {
            Log.debugf("[%s] processConfigUpdate: no %s in %s", ME, HausKeeperConfig.PATH, repoFullName);
            return map;
        }
        map = qc.readYamlContent(content, ProjectSourceConfig.TYPE_REF);
        if (map == null || qc.hasErrors()) {
            ctx.sendEmail(ME, "haus-keeper project aliases configuration could not be read", """
                    Source file %s could not be read (or parsed) from %s.

                    %s
                    """.formatted(projectList.filePath(),
                    projectList.repository(),
                    qc.bundleExceptions()),
                    qc.getErrorAddresses());
            return Map.of();
        }
        // All good. Project list exists, and we parsed it correctly.
        knownProjectDomains.set(map);
        return map;
    }

    private String repoToTaskGroup(String repoFullName) {
        return "%s-%s".formatted(ME, repoFullName);
    }

    private String taskGroupToRepo(String taskGroup) {
        return taskGroup.substring(ME.length() + 1);
    }

    @Override
    protected String me() {
        return ME;
    }

    record AliasConfigState(
            String taskGroup,
            String projectName,
            String repoFullName,
            long installationId,
            ProjectAliasMapping projectConfig) {
    }
}
