package org.commonhaus.automation.hm;

import static org.commonhaus.automation.github.context.GitHubQueryContext.toOrganizationName;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.config.RouteSupplier;
import org.commonhaus.automation.github.context.GitHubTeamService;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.hm.config.LatestOrgConfig;
import org.commonhaus.automation.hm.config.LatestProjectConfig;
import org.commonhaus.automation.hm.config.OrganizationConfig;
import org.commonhaus.automation.hm.config.OrganizationConfig.CollaboratorMonitorConfig;
import org.commonhaus.automation.hm.github.AppContextService;
import org.commonhaus.automation.queue.PeriodicUpdateQueue;
import org.commonhaus.automation.queue.ScheduledService;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class CollaboratorMonitor extends ScheduledService {
    static final String ME = "👥-collab";

    @Inject
    AppContextService ctx;

    @Inject
    GitHubTeamService teamService;

    @Inject
    PeriodicUpdateQueue updateQueue;

    @Inject
    LatestOrgConfig latestOrgConfig;

    @Inject
    LatestProjectConfig latestProjectConfig;

    @Override
    protected String me() {
        return ME;
    }

    void startup(@Observes StartupEvent startup) {
        RouteSupplier.registerSupplier("Collaborator monitoring refreshed", () -> lastRun);
    }

    /**
     * Periodically refresh/re-synchronize all project collaborators
     */
    // Quartz cron expression: s m h dom mon dow year(optional)
    @Scheduled(cron = "${automation.hausManager.cron.collaborators:0 47 9 */3 * ?}")
    public void scheduledRefresh() {
        Log.infof("[%s] ⏰ Scheduled: refresh collaborators", ME);
        refreshCollaborators(false);
    }

    public void refreshCollaborators(boolean userTriggered) {
        if (!userTriggered && !taskState.shouldRun(ME, Duration.ofHours(12))) {
            Log.infof("[%s]: skip scheduled collaborator refresh (last run: %s)", ME, lastRun);
            return;
        }
        updateQueue.queueReconciliation(ME, () -> reconcile());
    }

    private void reconcile() {
        recordRun();
        OrganizationConfig config = latestOrgConfig.getConfig();
        if (config == null || !config.isCollaboratorMonitorEnabled()) {
            Log.debugf("[%s] reconcile: configuration not available or collaborator monitor not enabled", ME);
            return;
        }

        CollaboratorMonitorConfig monitorConfig = config.collaboratorMonitor();
        Log.debugf("[%s] reconcile: start %s", ME, monitorConfig);

        // Gather all collaborators from project repositories
        try {
            Set<String> allCollaboratorLogins = gatherProjectCollaborators(config);
            if (allCollaboratorLogins.isEmpty()) {
                Log.debugf("[%s] reconcile: no collaborators found in project repositories", ME);
                return;
            }

            // Get the target repository
            String repoFullName = monitorConfig.allCollaboratorsRepository();
            String orgName = toOrganizationName(repoFullName);
            ScopedQueryContext qc = ctx.getOrgScopedQueryContext(orgName);
            if (qc == null) {
                Log.warnf("[%s] reconcile: no query context for target repository %s", ME, repoFullName);
                return;
            }

            GHOrganization org = qc.getOrganization(orgName);
            if (org == null) {
                Log.warnf("[%s] reconcile: organization %s not found", ME, orgName);
                return;
            }

            GHRepository repo = qc.getRepository(repoFullName);
            if (repo == null) {
                Log.warnf("[%s] reconcile: repository %s not found", ME, repoFullName);
                return;
            }

            // Determine the role to assign
            GHOrganization.RepositoryRole role = teamService.toRole(ctx, ME,
                    "reconcile", monitorConfig.role(),
                    config.emailNotifications(), monitorConfig);

            // Add all collaborators to the target repository
            teamService.syncCollaborators(qc, repo, role,
                    allCollaboratorLogins, monitorConfig.ignoreUsers(),
                    monitorConfig.dryRun(), config.emailNotifications());

            Log.infof("[%s] reconcile: synchronized %d collaborators to %s", ME, allCollaboratorLogins.size(),
                    repoFullName);
        } catch (Exception e) {
            Log.errorf(e, "[%s] reconcile: error gathering project collaborators", ME);
            ctx.sendEmail(ME, "Error gathering project collaborators",
                    """
                            An error occurred while gathering collaborators from project repositories.

                            Error details: %s

                            Please investigate the issue.
                            """.formatted(e.getMessage()),
                    ctx.getErrorAddresses(config.emailNotifications()));
            return;
        }
    }

    /**
     * Gather all collaborators from project repositories listed in the organization
     * configuration.
     *
     * @param config Organization configuration
     * @return Set of unique collaborator logins across all project repositories
     */
    private Set<String> gatherProjectCollaborators(OrganizationConfig config) {
        Set<String> allCollaborators = new HashSet<>();

        var qc = ctx.getHomeQueryContext();
        if (qc == null) {
            Log.debugf("[%s] gatherProjectCollaborators: no home query context available", ME);
            throw new IllegalStateException("Unable to get home query context.");
        }

        // Iterate through all configured projects
        for (var projectState : latestProjectConfig.getAllProjects()) {
            var projectConfig = projectState == ProjectManager.EMPTY
                    ? null
                    : projectState.projectConfig();
            if (projectConfig == null) {
                Log.errorf("Uninitialized project. Deferring collaborator gathering.");
                throw new IllegalStateException("Uninitialized project state encountered.");
            }

            var teamAccess = projectConfig.collaboratorSync();
            if (teamAccess == null) {
                Log.debugf("[%s] gatherProjectCollaborators: project has no collaboratorSync configured", ME);
                continue;
            }

            var projectRepoFullName = projectState.repoFullName();
            GHRepository projectRepo = qc.getRepository(projectRepoFullName);
            if (projectRepo == null) {
                Log.debugf("[%s] gatherProjectCollaborators: repository %s not found",
                        ME, projectRepoFullName);
                throw new IllegalStateException("Invalid project repository: " + projectRepoFullName);
            }

            // Get collaborators from this project repository
            Set<String> projectCollaborators = teamService.getCollaboratorLogins(qc, projectRepo);
            allCollaborators.addAll(projectCollaborators);
            Log.debugf("[%s] gatherProjectCollaborators: found %d collaborators in %s",
                    ME, projectCollaborators.size(), projectRepoFullName);
        }

        Log.infof("[%s] gatherProjectCollaborators: total unique collaborators: %d", ME, allCollaborators.size());
        return allCollaborators;
    }
}
