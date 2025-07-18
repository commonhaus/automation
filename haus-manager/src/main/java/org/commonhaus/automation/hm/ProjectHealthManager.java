package org.commonhaus.automation.hm;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.config.BotConfig;
import org.commonhaus.automation.config.RouteSupplier;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.github.stats.ProjectHealthCollector;
import org.commonhaus.automation.hm.ProjectManager.ProjectConfigState;
import org.commonhaus.automation.hm.config.LatestProjectConfig;
import org.commonhaus.automation.hm.config.LatestProjectConfig.ProjectConfigListener;
import org.commonhaus.automation.hm.config.ProjectConfig.RepositoryConfig;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;

public class ProjectHealthManager extends GroupCoordinator implements ProjectConfigListener {
    static final String ME = "🩺-health";

    @Inject
    protected BotConfig baseBotConfig;

    @Inject
    LatestProjectConfig latestProjectConfig;

    @Inject
    ProjectHealthCollector projectHealthCollector;

    void startup(@Observes StartupEvent startup) {
        RouteSupplier.registerSupplier("Project health refreshed", () -> lastRun);
        // Notify when project config changes
        latestProjectConfig.notifyOnUpdate(ME, this);
    }

    @Override
    public void onProjectConfigUpdate(String healthTaskGroup, ProjectConfigState state) {
        Log.debugf("[%s] Project config updated for %s", ME, healthTaskGroup);

        if (!state.healthCollectionEnabled()) {
            Log.debugf("[%s] %s: Health collection disabled, skipping", ME, healthTaskGroup);
            return;
        }

        // Queue health collection for this specific project
        updateQueue.queueReconciliation(healthTaskGroup,
                () -> doCollectProjectHealth(healthTaskGroup, state.repoName(), false, LocalDate.now()));
    }

    @Scheduled(cron = "0 0 6 ? * SUN") // Sunday 6 AM
    public void weeklyHealthCollection() {
        try {
            Log.infof("[%s] ⏰ Scheduled: begin collection of project health data", ME);
            // Safe anchor ensures we always get the Sunday of the completed week
            LocalDate safeDay = LocalDateTime.now().with(TemporalAdjusters.previous(java.time.DayOfWeek.FRIDAY))
                    .toLocalDate();
            // This will work from the Sunday before our safe day.
            collectHeathData(false, safeDay);
        } catch (Throwable t) {
            ctx.logAndSendEmail(ME, "⏰ 🌳 Error running scheduled config refresh", t);
        }
    }

    /**
     * Allow manual trigger from admin endpoint
     */
    public void collectHeathData(boolean userTriggered, LocalDate anchorDate) {
        if (!userTriggered && !taskState.shouldRun(ME, Duration.ofDays(1))) {
            Log.infof("[%s]: skip scheduled project health update (last run: %s)", ME, lastRun);
            return;
        }
        recordRun();
        for (var state : latestProjectConfig.getAllProjects()) {
            var healthTaskGroup = getTaskGroup(state.repoName());
            // do this in chunks to space the work out..
            updateQueue.queueReconciliation(healthTaskGroup,
                    () -> doCollectProjectHealth(healthTaskGroup, state.repoName(), userTriggered, anchorDate));
        }
    }

    private void doCollectProjectHealth(String healthTaskGroup, String repoFullName, boolean userTriggered,
            LocalDate anchorDate) {
        var state = latestProjectConfig.getProjectConfigState(repoFullName);
        if (state == null) {
            Log.warnf("[%s] %s: No project config state found for %s", ME, healthTaskGroup, repoFullName);
            return;
        }
        if (!state.healthCollectionEnabled()) {
            Log.debugf("[%s] %s: Health collection is disabled for %s", ME, healthTaskGroup, repoFullName);
            return;
        }

        var config = state.projectConfig();
        var projectHealth = config.projectHealth();
        Log.debugf("[%s] %s: Collecting project health data for %s organizations",
                ME, state.taskGroup(), projectHealth.organizationRepositories().size());

        // Collect health data for each configured organization
        for (var orgEntry : projectHealth.organizationRepositories().entrySet()) {
            String orgName = orgEntry.getKey();
            var repoConfig = orgEntry.getValue();
            String orgTaskGroup = orgRepoHealthReport(orgName);
            ;

            updateQueue.queueReconciliation(orgTaskGroup, () -> {
                collectOrganizationHealth(anchorDate, state.installationId(), orgName, repoConfig);
            });
        }
    }

    private void collectOrganizationHealth(LocalDate anchorDate, long installationId, String orgName,
            RepositoryConfig repoConfig) {
        ScopedQueryContext orgQc = new ScopedQueryContext(ctx, installationId, orgName, null);
        var organization = orgQc.getOrganization();
        if (organization == null || orgQc.hasErrors()) {
            orgQc.logAndSendContextErrors("[%s] unable to list repositories for %s".formatted(ME, orgName));
            return;
        }

        Log.debugf("[%s] Discovering repositories for organization %s", ME, orgName);

        // Get all repositories in the organization
        var allRepos = orgQc.execGitHubSync((gh, dr) -> {
            return organization.listRepositories().toList();
        });
        if (orgQc.hasErrors()) {
            orgQc.logAndSendContextErrors("[%s] unable to list repositories for %s".formatted(ME, orgName));
            return;
        }

        // Filter repositories based on configuration
        var trackedRepos = allRepos.stream()
                .filter(repo -> !repoConfig.isExcluded(repo.getName()))
                .filter(repo -> !repo.isArchived()) // Skip archived repositories
                .toList();

        Log.debugf("[%s] Found %d repositories to track in %s (excluded %d)",
                ME, trackedRepos.size(), orgName, allRepos.size() - trackedRepos.size());

        // Collect health data for each tracked repository
        for (var repo : trackedRepos) {
            String fullName = repo.getFullName();
            String reportTaskGroup = orgRepoHealthReport(fullName);
            String expectedFrequency = repoConfig.getReleaseFrequency(repo.getName());
            updateQueue.queueReconciliation(reportTaskGroup, () -> {
                collectRepoHealth(anchorDate, installationId, fullName, expectedFrequency);
            });
        }
    }

    private void collectRepoHealth(LocalDate anchorDate, long installationId, String repoFullName,
            String expectedFrequency) {
        ScopedQueryContext qc = new ScopedQueryContext(ctx, installationId, repoFullName);
        Log.debugf("[%s] Collecting health data for %s (expected frequency: %s)", ME, repoFullName, expectedFrequency);
        var report = projectHealthCollector.collect(qc, anchorDate);

        // TODO: Write report to file, including expected frequency metadata
        Log.debugf("[%s] Collected health report for %s: %s", ME, repoFullName, report);
    }

    @Override
    public String getTaskGroup(String repoFullName) {
        return "health#" + repoFullName;
    }

    private static String orgRepoHealthReport(String orgOrRepoName) {
        return "healthReport#" + orgOrRepoName;
    }

    @Override
    protected String me() {
        return ME;
    }
}
