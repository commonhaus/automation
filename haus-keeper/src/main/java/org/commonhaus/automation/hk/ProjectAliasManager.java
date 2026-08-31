package org.commonhaus.automation.hk;

import static org.commonhaus.automation.github.context.GitHubQueryContext.toOrganizationName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

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
import org.commonhaus.automation.hk.config.ProjectAliasMapping;
import org.commonhaus.automation.hk.config.ProjectAliasMapping.UserAliasList;
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

    /**
     * Sentinel projectConfig value meaning "read attempted, no
     * project-mail-aliases.yml found in this repo" -- distinct from a
     * not-yet-read placeholder (projectConfig == null). Its hasUserMapping()/
     * hasDomains() are correctly false, so it behaves like an empty config
     * everywhere it's used; the only reason it exists is so callers can tell
     * "nothing to read yet" apart from "confirmed nothing to read, ever."
     */
    private static final ProjectAliasMapping CONFIRMED_ABSENT = new ProjectAliasMapping(null, null, null, null);

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

    void startup(@Observes @Priority(value = RdePriority.APP_DISCOVERY) StartupEvent startup) {
        RouteSupplier.registerSupplier("Project aliases refreshed", () -> lastRun);
        hkConfig.notifyOnDomainChange(ME, this::reconcileChangedProjects);
    }

    private void reconcileChangedProjects(Set<String> changedProjectNames) {
        for (var entry : taskGroupToState.entrySet()) {
            String taskGroup = entry.getKey();
            AliasConfigState state = entry.getValue();
            if (state == null || !changedProjectNames.contains(state.projectName())) {
                continue;
            }
            if (state.projectConfig() == null) {
                // Lazily-discovered project: no config read yet. Read it now rather
                // than silently dropping this domain change until the next cron pass.
                updateQueue.queue(taskGroup, () -> readProjectConfig(taskGroup, state.repoFullName()));
            } else {
                updateQueue.queueReconciliation(taskGroup, () -> reconcile(taskGroup));
            }
        }
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
        if (!userTriggered && !taskState.shouldRun(ME, Duration.ofHours(12))) {
            Log.infof("[%s]: skip scheduled project refresh (last run: %s)", ME, lastRun);
            return;
        }
        recordRun();
        for (var entry : taskGroupToState.entrySet()) {
            readProjectConfig(entry.getKey(), taskGroupToRepo(entry.getKey()));
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

        long installationId = repoEvent.installationId();

        // We only read configuration files from repositories in the configured organization
        if (action.repository() && orgName.equals(adminBotConfig.home().organization())) {
            final String taskGroup = repoToTaskGroup(repoFullName);

            if (action.added()) {
                ScopedQueryContext qc = new ScopedQueryContext(ctx, installationId, repo)
                        .withExisting(repoEvent.github());

                if (!hkConfig.isReady()) {
                    updateQueue.queue(taskGroup, () -> repositoryDiscovered(repoEvent));
                } else if (taskState.shouldRun(ME, Duration.ofHours(12))) {
                    updateQueue.queue(taskGroup, () -> readProjectConfig(taskGroup, qc, true));
                } else {
                    Log.debug("Skip eager project discovery (ran recently); lazy discovery on updates/cron");
                    taskGroupToState.put(taskGroup,
                            new AliasConfigState(taskGroup, toProjectName(repoFullName), repoFullName, installationId, null));
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
            taskGroupToState.put(taskGroup,
                    new AliasConfigState(taskGroup, toProjectName(repoFullName), repoFullName,
                            fileUpdate.installationId(), CONFIRMED_ABSENT));
            return;
        }

        ScopedQueryContext qc = new ScopedQueryContext(ctx, fileUpdate.installationId(), fileUpdate.repository());
        readProjectConfig(taskGroup, qc, true);
    }

    /**
     * Build a fresh ScopedQueryContext for repoFullName and read its project
     * config, queuing reconciliation on success. Shared by callers that only
     * have a repo name in hand (scheduled/manual refresh, retroactive
     * re-validation of a not-yet-read project) rather than an existing qc.
     */
    private void readProjectConfig(String taskGroup, String repoFullName) {
        ScopedQueryContext qc = ctx.getScopedQueryContext(repoFullName);
        qc.getRepository(repoFullName);
        readProjectConfig(taskGroup, qc, true);
    }

    protected void readProjectConfig(String taskGroup, ScopedQueryContext qc, boolean queueReconciliation) {
        // The repository containing the (added/modified) file must be present in the query context
        String repoFullName = taskGroupToRepo(taskGroup);
        GHRepository repo = qc.getRepository(repoFullName);
        if (repo == null || qc.hasErrors()) {
            Log.warnf("%s readProjectConfig: repository not set in QueryContext: %s", taskGroup, qc.bundleExceptions());
            return;
        }

        String projectName = toProjectName(repoFullName);

        GHContent content = qc.readSourceFile(repo, ProjectAliasMapping.CONFIG_FILE);
        if (qc.hasErrors()) {
            // Transient/access error, not a confirmed absence -- leave existing
            // state as-is so this is retried rather than recorded as permanent.
            Log.debugf("%s readProjectConfig: error reading %s in %s: %s", taskGroup,
                    ProjectAliasMapping.CONFIG_FILE, repoFullName, qc.bundleExceptions());
            return;
        }
        if (content == null) {
            // Confirmed: no project-mail-aliases.yml in this repo. Record that
            // distinctly from "not read yet" so a later domain-change event
            // doesn't keep re-reading a repo that will never have aliases.
            Log.debugf("%s readProjectConfig: no %s in %s", taskGroup,
                    ProjectAliasMapping.CONFIG_FILE, repoFullName);
            taskGroupToState.put(taskGroup,
                    new AliasConfigState(taskGroup, projectName, repoFullName, qc.getInstallationId(), CONFIRMED_ABSENT));
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
        Log.debugf("%s readProjectConfig: ✔️ found %s in %s", taskGroup, ProjectAliasMapping.CONFIG_FILE,
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

        ScopedQueryContext qc = new ScopedQueryContext(ctx, state.installationId(), state.repoFullName());
        ProjectAliasMapping projectAliasConfig = state.projectConfig();
        Set<String> domains = projectAliasConfig.hasDomains() ? projectAliasConfig.domains() : Set.of();

        if (!projectAliasConfig.hasUserMapping()) {
            Log.debugf("%s: no user mappings defined in project alias config", taskGroup);
            return;
        }

        // Restrict to authoritative domains, if we have any cached for this project.
        // An empty authoritative set means "no data yet / not configured" (fail-open),
        // not "no domains are allowed" -- so it must leave domains unfiltered.
        Set<String> authoritativeDomains = hkConfig.getDomainsForProject(state.projectName());
        if (!authoritativeDomains.isEmpty()) {
            domains = domains.stream()
                    .filter(authoritativeDomains::contains)
                    .collect(Collectors.toSet());
        }

        List<InvalidAlias> invalidAliases = new ArrayList<>();

        // For each user in the mapping, ensure their aliases exist and are up to date
        for (UserAliasList userAliases : projectAliasConfig.userMapping()) {
            String login = userAliases.login();
            if (userAliases.aliases().isEmpty()) {
                Log.debugf("%s: no aliases defined for login %s", taskGroup, login);
                continue;
            }
            GHUser ghUser = login == null ? null : qc.getUser(login);

            if (qc.hasErrors()) {
                qc.logAndSendEmail("Error fetching user from GitHub",
                        "%s: error fetching user %s".formatted(taskGroup, login),
                        qc.bundleExceptions(),
                        projectAliasConfig.emailNotifications());
                return; // stop processing. We will try again later (e.g the next cron run or after a fix)
            } else if (ghUser == null || !userAliases.isValid(domains)) {
                Log.debugf("%s: invalid aliases for login %s (%s)", taskGroup, userAliases, ghUser);
                String reason = ghUser == null
                        ? "GitHub user not found"
                        : "one or more aliases use a domain that is not authorized for this project";
                invalidAliases.add(new InvalidAlias(userAliases.login(), userAliases.aliases(), reason));
                continue;
            }

            try {
                // Create a new user object if it does not exist
                CommonhausUser user = datastore.getCommonhausUser(login, ghUser.getId(), false, true);

                if (user.aliasesMatch(state.projectName(), domains, userAliases.aliases())) {
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
                        "Update user aliases",
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

        if (!invalidAliases.isEmpty()) {
            String title = "Invalid alias(es) defined";
            StringBuilder findings = new StringBuilder();
            for (InvalidAlias invalid : invalidAliases) {
                findings.append("""

                        Login: %s
                        Aliases: %s
                        Reason: %s
                        """.formatted(invalid.login(), invalid.aliases(), invalid.reason()));
            }
            String message = """

                    The following aliases are invalid:
                    %s
                    Aliases should be fully qualified email addresses for one of the following:
                    %s.

                    Project config: %s
                    """.formatted(findings, domains, projectAliasConfig);

            ctx.sendEmail(ME, title, message,
                    qc.getErrorAddresses(projectAliasConfig.emailNotifications()));
            qc.createItem(EventType.issue, title, message, null);
        }
        Log.debugf("%s: project alias sync complete; %s", taskGroup, state.projectConfig());
    }

    void notifyUserProjects(@Observes LoginChangeEvent loginChangeEvent) {
        // This event is fired when a user changes their login
        // Any projects that the user is a member of should be notified
        // so configurations can be modified.
        for (var project : loginChangeEvent.projects()) {
            for (var entry : taskGroupToState.entrySet()) {
                String taskGroup = entry.getKey();
                String repoFullName = taskGroupToRepo(taskGroup);
                String projectName = toProjectName(repoFullName);
                if (projectName.equals(project)) {
                    var state = entry.getValue();
                    if (state == null || state.projectConfig() == null) {
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

    static String toProjectName(String repoFullName) {
        int slash = repoFullName.lastIndexOf('/');
        String repoName = slash >= 0 ? repoFullName.substring(slash + 1) : repoFullName;
        if (repoName.startsWith("project-")) {
            repoName = repoName.substring("project-".length());
        }
        return repoName;
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

    record InvalidAlias(
            String login,
            Set<String> aliases,
            String reason) {
    }
}
