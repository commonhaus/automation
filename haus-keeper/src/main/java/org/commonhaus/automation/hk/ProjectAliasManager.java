package org.commonhaus.automation.hk;

import static org.commonhaus.automation.github.context.GitHubQueryContext.toOrganizationName;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.config.RepoSource;
import org.commonhaus.automation.config.RouteSupplier;
import org.commonhaus.automation.github.context.GitHubTeamService;
import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent.RdePriority;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.github.watchers.FileWatcher;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdate;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdateType;
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
import org.commonhaus.automation.queue.TaskStateService;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class ProjectAliasManager {
    private static final String ME = "📫-aliases";
    private static volatile String lastRun = "never";
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
    TaskStateService taskState;

    @Inject
    GitHubTeamService teamService;

    // flat map of task group to its current state
    final Map<String, AliasConfigState> taskGroupToState = new ConcurrentHashMap<>();
    final AtomicReference<Map<String, ProjectSourceConfig>> projectToDomainMap = new AtomicReference<>();

    void startup(@Observes @Priority(value = RdePriority.APP_DISCOVERY) StartupEvent startup) {
        lastRun = Optional.ofNullable(taskState.lastRun(ME))
                .map(Instant::toString)
                .orElse("never");
        RouteSupplier.registerSupplier("Project aliases refreshed", () -> lastRun);

        hkConfig.notifyOnUpdate(ME, () -> {
            Log.infof("HausKeeper project aliases config updated: %s", hkConfig.getProjectAliasesConfig());
            updateDomainMap();
        });
    }

    private void recordRun() {
        lastRun = taskState.recordRun(ME).toString();
    }

    /**
     * Periodically refresh/re-synchronize team aliases.
     */
    // Quartz cron expression: s m h dom mon dow year(optional)
    @Scheduled(cron = "${automation.hausKeeper.cron.projectAliases:0 47 4 */3 * ?}")
    public void scheduledRefresh() {
        try {
            Log.infof("[%s] ⏰ Scheduled: begin refresh project aliases", ME);
            refreshProjectAliases();
        } catch (Throwable t) {
            ctx.logAndSendEmail(ME, "📫 ⏰ Error running scheduled refresh of project aliases", t);
        }
    }

    /**
     * Allow manual trigger by admin endpoint
     */
    public void refreshProjectAliases() {
        recordRun();
        for (var state : taskGroupToState.values()) {
            ScopedQueryContext qc = new ScopedQueryContext(ctx, state.installationId(), state.repoFullName());
            readProjectConfig(state.taskGroup(), qc);
        }
    }

    /**
     * Event handler for repository discovery.
     */
    protected void repositoryDiscovered(
            @Observes @Priority(value = RdePriority.APP_DISCOVERY + 2) RepositoryDiscoveryEvent repoEvent) {
        recordRun();

        DiscoveryAction action = repoEvent.action();
        GHRepository repo = repoEvent.repository();
        String repoFullName = repo.getFullName();
        String orgName = toOrganizationName(repoFullName);

        Log.debugf("[%s] repositoryDiscovered (%s): %s", ME, repoEvent.action(), repoFullName);
        long installationId = repoEvent.installationId();

        // We only read configuration files from repositories in the configured organization
        if (action.repository() && orgName.equals(adminBotConfig.home().organization())) {
            final String taskGroup = "%s-%s".formatted(ME, repoFullName);

            if (action.added()) {
                ScopedQueryContext qc = new ScopedQueryContext(ctx, installationId, repo)
                        .withExisting(repoEvent.github());

                if (!hkConfig.isReady()) {
                    updateQueue.queue(taskGroup, () -> repositoryDiscovered(repoEvent));
                } else if (taskState.shouldRun(ME, Duration.ofHours(6))) {
                    updateQueue.queue(taskGroup, () -> readProjectConfig(taskGroup, qc));
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
        readProjectConfig(taskGroup, qc);
    }

    protected void readProjectConfig(String taskGroup, ScopedQueryContext qc) {
        AliasManagementConfig aliasMgmtConfig = hkConfig.getProjectAliasesConfig();
        if (aliasMgmtConfig.isDisabled()) {
            Log.debugf("[%s] %s: project aliases sync disabled", ME, taskGroup);
            return;
        }

        // The repository containing the (added/modified) file must be present in the query context
        GHRepository repo = qc.getRepository();
        if (repo == null) {
            Log.warnf("[%s] %s readProjectConfig: repository not set in QueryContext", ME, taskGroup);
            return;
        }

        String projectName = aliasMgmtConfig.toProjectName(repo.getFullName());
        ProjectSourceConfig projectConfig = getCentralProjectConfig(projectName);
        if (projectConfig == null) {
            Log.debugf("[%s] %s readProjectConfig: Repository %s does not map to a known project", ME, taskGroup,
                    repo.getFullName());
            taskGroupToState.remove(taskGroup);
            return;
        }

        GHContent content = qc.readSourceFile(repo, ProjectAliasMapping.CONFIG_FILE);
        if (content == null || qc.hasErrors()) {
            // Normal
            Log.debugf("[%s] %s readProjectConfig: no %s in %s", ME, taskGroup, ProjectAliasMapping.CONFIG_FILE,
                    repo.getFullName());
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
        Log.debugf("[%s] %s readProjectConfig: found %s in %s", ME, taskGroup, ProjectAliasMapping.CONFIG_FILE,
                repo.getFullName());

        AliasConfigState newState = new AliasConfigState(taskGroup,
                projectName, repo.getFullName(),
                qc.getInstallationId(), aliasConfig);

        taskGroupToState.put(taskGroup, newState);

        // queue reconcile action: deal with bursty config updates
        updateQueue.queueReconciliation(taskGroup, () -> reconcile(taskGroup));
    }

    public void reconcile(String taskGroup) {
        recordRun();

        // Always fetch latest state (in case of changes / skips)
        AliasConfigState state = taskGroupToState.get(taskGroup);
        if (state == null || state.projectConfig() == null) {
            Log.debugf("[%s] %s: no state or project config to reconcile", ME, taskGroup);
            return;
        }

        Log.debugf("[%s] %s: aliases sync; %s", ME, taskGroup, state.projectConfig());

        ProjectSourceConfig centralProjectConfig = getCentralProjectConfig(state.projectName());
        if (centralProjectConfig == null) {
            Log.debugf("[%s] %s: project config not found for %s", ME, taskGroup, state.projectName());
            taskGroupToState.remove(taskGroup);
            return;
        }

        ScopedQueryContext qc = new ScopedQueryContext(ctx, state.installationId(), state.repoFullName());
        ProjectAliasMapping projectAliasConfig = state.projectConfig();

        String definitiveMailDomain = centralProjectConfig.mailDomain();
        String projectDomain = projectAliasConfig.domain();
        if (!Objects.equals(definitiveMailDomain, projectDomain)) {
            Log.debugf("[%s] %s: project domain mismatch; %s != %s", ME, taskGroup, projectDomain, definitiveMailDomain);
            ctx.sendEmail(ME, "HausKeeper project alias domain mismatch", """
                    Project alias domain mismatch for %s

                    Project alias domain: %s
                    Central project alias domain: %s
                    """.formatted(state.repoFullName(), projectDomain, definitiveMailDomain),
                    qc.getErrorAddresses(projectAliasConfig.emailNotifications()));
            return;
        }

        if (projectAliasConfig.userMapping() == null || projectAliasConfig.userMapping().isEmpty()) {
            Log.debugf("[%s] %s: no user mappings defined in project alias config", ME, taskGroup);
            return;
        }

        // For each user in the mapping, ensure their aliases exist and are up to date
        for (UserAliasList userAliases : projectAliasConfig.userMapping()) {
            String login = userAliases.login();
            if (userAliases.aliases().isEmpty()) {
                Log.debugf("[%s] %s: no aliases defined for login %s", ME, taskGroup, login);
                continue;
            }
            GHUser ghUser = login == null ? null : qc.getUser(login);

            // If anything about the user alias is wrong, send an email and skip it
            if (qc.hasErrors()) {
                qc.logAndSendContextErrors("Error fetching user %s".formatted(login),
                        projectAliasConfig.emailNotifications());
                return; // stop processing. We will try again later (e.g the next cron run or after a fix)
            } else if (ghUser == null || !userAliases.isValid(projectDomain)) {
                Log.debugf("[%s] %s: invalid aliases for login %s (%s)", ME, taskGroup, userAliases, ghUser);
                ctx.sendEmail(ME, "Invalid alias defined", """

                        Login: %s
                        Aliases: %s

                        Aliases should be fully qualified email addresses ending with @%s.

                        Project config: %s
                        """.formatted(state.repoFullName(),
                        userAliases.login(),
                        userAliases.aliases(),
                        projectDomain,
                        projectAliasConfig),
                        qc.getErrorAddresses(projectAliasConfig.emailNotifications()));
                continue;
            }

            try {
                // Create a new user object if it does not exist
                CommonhausUser user = datastore.getCommonhausUser(login, ghUser.getId(), false, true);

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
                Log.debugf("[%s] %s: error updating user %s: %s", ME, taskGroup, login, qc.bundleExceptions());
                qc.logAndSendContextErrors("Error adding project email aliases",
                        projectAliasConfig.emailNotifications());
                return; // stop processing. We will try again later (e.g the next cron run or after a fix)
            } else {
                Log.debugf("[%s] %s: updated user %s with aliases %s", ME, taskGroup, login, userAliases.aliases());
            }
        }
        Log.debugf("[%s] %s: project alias sync complete; %s", ME, taskGroup, state.projectConfig());
    }

    private ProjectSourceConfig getCentralProjectConfig(String projectName) {
        Map<String, ProjectSourceConfig> map = projectToDomainMap.get();
        if (map == null) {
            map = updateDomainMap();
        }
        ProjectSourceConfig config = map.get(projectName);
        if (config == null) {
            Log.warnf("[%s] getCentralProjectConfig: no project list found for %s", ME, projectName);
        }
        return config;
    }

    private Map<String, ProjectSourceConfig> updateDomainMap() {
        Map<String, ProjectSourceConfig> map = Map.of();
        projectToDomainMap.set(map); // ensure non-null

        AliasManagementConfig aliasMgmtConfig = hkConfig.getProjectAliasesConfig();
        if (aliasMgmtConfig.isDisabled()) {
            return map;
        }
        RepoSource projectList = aliasMgmtConfig.projectList();
        ScopedQueryContext qc = ctx.getScopedQueryContext(projectList.repository());
        if (qc == null || qc.getRepository() == null) {
            Log.warnf("[%s] updateDomainMap: no query context for %s", ME, projectList);
            return map;
        }
        GHContent content = qc.readSourceFile(qc.getRepository(), projectList.filePath());
        if (content == null || qc.hasErrors()) {
            Log.debugf("[%s] processConfigUpdate: no %s in %s", ME, HausKeeperConfig.PATH, projectList.repository());
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
        projectToDomainMap.set(map);
        return map;
    }

    record AliasConfigState(
            String taskGroup,
            String projectName,
            String repoFullName,
            long installationId,
            ProjectAliasMapping projectConfig) {
    }
}
