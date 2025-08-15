package org.commonhaus.automation.hm;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.config.BotConfig;
import org.commonhaus.automation.config.RepoSource;
import org.commonhaus.automation.config.RouteSupplier;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.github.stats.ProjectHealthCollector;
import org.commonhaus.automation.hm.ProjectManager.ProjectConfigState;
import org.commonhaus.automation.hm.config.LatestProjectConfig;
import org.commonhaus.automation.hm.config.LatestProjectConfig.ProjectConfigListener;
import org.commonhaus.automation.hm.config.ProjectConfig.RepositoryHealthConfig;
import org.commonhaus.automation.hm.github.ProjectHealthBatch;
import org.commonhaus.automation.hm.github.ReportQueryContext;
import org.commonhaus.automation.mail.LogMailer;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class ProjectHealthManager extends GroupCoordinator implements ProjectConfigListener {
    static final String ME = "🩺-health";

    @Inject
    protected BotConfig baseBotConfig;

    @Inject
    LatestProjectConfig latestProjectConfig;

    @Inject
    ProjectHealthCollector projectHealthCollector;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    LogMailer logMailer;

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
                () -> collectAndCommitProjectHealth(healthTaskGroup, state, false, LocalDate.now()));
    }

    @Scheduled(cron = "0 0 6 ? * SUN") // Sunday 6 AM
    public void weeklyHealthCollection() {
        try {
            Log.infof("[%s] ⏰ Scheduled: begin collection of project health data", ME);
            // Safe anchor ensures we always get the Sunday of the completed week
            LocalDate safeDay = LocalDateTime.now().with(TemporalAdjusters.previous(java.time.DayOfWeek.FRIDAY))
                    .toLocalDate();
            // This will work from the Sunday before our safe day.
            collectHealthData(false, safeDay);
        } catch (Throwable t) {
            ctx.logAndSendEmail(ME, "⏰ 🌳 Error running scheduled config refresh", t);
        }
    }

    /**
     * Allow manual trigger from admin endpoint
     */
    public void collectHealthData(boolean userTriggered, LocalDate anchorDate) {
        if (!userTriggered && !taskState.shouldRun(ME, Duration.ofDays(1))) {
            Log.infof("[%s]: skip scheduled project health update (last run: %s)", ME, lastRun);
            return;
        }
        Log.infof("[%s]: collect health data (last run: %s)", ME, lastRun);
        recordRun();

        var allProjects = latestProjectConfig.getAllProjects();
        Log.debugf("[%s]: found %d total projects", ME, allProjects.size());
        for (var state : allProjects) {
            var healthTaskGroup = getTaskGroup(state.repoFullName());
            if (!state.healthCollectionEnabled()) {
                Log.debugf("[%s] %s: Health collection is disabled for %s", ME, healthTaskGroup, state.repoFullName());
                continue;
            }

            Log.debugf("[%s]: processing project %s: %s", ME, state.repoFullName(), state.projectConfig());
            final var taskState = state;
            Runnable task = () -> collectAndCommitProjectHealth(healthTaskGroup, taskState, userTriggered, anchorDate);
            if (userTriggered) {
                // User triggered: add batch to work queue
                updateQueue.queueReconciliation(healthTaskGroup, task);
            } else {
                // Queue batch collection as a background task
                // These tasks will run when the regular task queue has been drained.
                updateQueue.queueBackground(healthTaskGroup, task);
            }
        }
    }

    void collectAndCommitProjectHealth(String healthTaskGroup, ProjectConfigState state, boolean userTriggered,
            LocalDate anchorDate) {
        var config = state.projectConfig();
        var projectHealth = config.projectHealth();
        var repositories = projectHealth.organizationRepositories();

        // Create source context for writing reports to the source repository
        ReportQueryContext rqc = ctx.getReportQueryContext(state.repoFullName());

        // Create batch for this project's health collection
        ProjectHealthBatch batch = new ProjectHealthBatch(state, anchorDate, rqc, objectMapper);

        // Synchronously collect health data for repositories in each configured organization
        for (var orgEntry : repositories.entrySet()) {
            String orgName = orgEntry.getKey();
            var repoConfig = orgEntry.getValue();

            collectOrganizationHealth(batch, anchorDate, orgName, repoConfig);
        }

        // Commit all collected reports in a single operation
        batch.commitAllReports();
    }

    private void collectOrganizationHealth(ProjectHealthBatch batch, LocalDate anchorDate,
            String orgName, RepositoryHealthConfig repoConfig) {
        ScopedQueryContext orgQc = new ScopedQueryContext(ctx, batch.installationId(), orgName, null);
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
                .filter(repo -> repoConfig.isIncluded(repo.getName()))
                .filter(repo -> !repo.isArchived()) // Skip archived repositories
                .toList();

        Log.debugf("[%s] Found %d repositories to track in %s (excluded %d)",
                ME, trackedRepos.size(), orgName, allRepos.size() - trackedRepos.size());

        // Synchronously collect health data for each tracked repository
        for (var repo : trackedRepos) {
            String fullName = repo.getFullName();
            collectRepoHealth(batch, anchorDate, fullName, repoConfig);
        }
    }

    private void collectRepoHealth(ProjectHealthBatch batch, LocalDate anchorDate, String repoFullName,
            RepositoryHealthConfig repoConfig) {
        String expectedFrequency = repoConfig.getReleaseFrequency(repoFullName);

        ScopedQueryContext qc = new ScopedQueryContext(ctx, batch.installationId(), repoFullName);
        Log.debugf("[%s] Collecting health data for %s (expected frequency: %s)", ME, repoFullName, expectedFrequency);

        var report = projectHealthCollector.collect(qc, anchorDate, true, true);
        if (qc.hasErrors()) {
            qc.logAndSendContextErrors(String.format("[%s] Failed to collect health data for %s", ME, repoFullName));
            return;
        }

        // Add the report to the batch (batch handles dry run internally)
        batch.addReport(repoFullName, report);
        Log.debugf("[%s] Added health report for %s to batch", ME, repoFullName);
    }

    @Override
    public String getTaskGroup(String repoFullName) {
        return "health#" + repoFullName;
    }

    @Override
    protected String me() {
        return ME;
    }

    @Override
    protected void processRepoSourceUpdate(String taskGroup, RepoSource repoSource) {
    }
}
