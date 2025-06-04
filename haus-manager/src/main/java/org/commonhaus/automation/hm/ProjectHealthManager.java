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
        RouteSupplier.registerSupplier("Sponsor management refreshed", () -> lastRun);
        // Notify when project config changes
        latestProjectConfig.notifyOnUpdate(ME, this);
    }

    @Override
    public void onProjectConfigUpdate(String healthTaskGroup, ProjectConfigState projectConfig) {
        Log.debugf("[%s] Project config updated for %s", ME, healthTaskGroup);

        if (!projectConfig.healthCollectionEnabled()) {
            Log.debugf("[%s] %s: Health collection disabled, skipping", ME, healthTaskGroup);
            return;
        }

        // Queue health collection for this specific project
        updateQueue.queueReconciliation(healthTaskGroup,
                () -> doCollectProjectHealth(healthTaskGroup, false, LocalDate.now()));
    }

    @Scheduled(cron = "0 0 6 ? * SUN") // Sunday 6 AM
    public void weeklyHealthCollection() {
        try {
            Log.infof("[%s] ⏰ Scheduled: begin collection of project health data", ME);
            LocalDate safeDay = LocalDateTime.now().with(TemporalAdjusters.previous(java.time.DayOfWeek.FRIDAY)).toLocalDate();
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
            var taskGroup = repotoHealthGroup(state.repoName());
            // do this in chunks to space the work out..
            updateQueue.queueReconciliation(taskGroup, () -> doCollectProjectHealth(taskGroup, userTriggered, anchorDate));
        }
    }

    private void doCollectProjectHealth(String healthTaskGroup, boolean userTriggered, LocalDate anchorDate) {
        var repoFullName = healthGroupToRepo(healthTaskGroup);
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
        Log.debugf("[%s] %s: Collecting project health data for %s repositories",
                ME, state.taskGroup(), projectHealth.trackedRepositories().size());

        for (String repoPattern : projectHealth.trackedRepositories()) {
            updateQueue.queueReconciliation("%s::%s".formatted(healthTaskGroup, repoPattern), () -> {
                this.collectRepoHealth(anchorDate, state.installationId(), state.repoName());
            });
        }
    }

    private void collectRepoHealth(LocalDate anchorDate, long installationId, String repoFullName) {
        ScopedQueryContext qc = new ScopedQueryContext(ctx, installationId, repoFullName);
        Log.debugf("[%s] Collecting health data for %s", ME, repoFullName);
        var report = projectHealthCollector.collect(qc, anchorDate);
    }

    @Override
    public String getTaskGroup(String projectName) {
        return repotoHealthGroup(projectName);
    }

    @Override
    protected String me() {
        return ME;
    }

    private static String repotoHealthGroup(String repoFullName) {
        return "health#" + repoFullName;
    }

    private static String healthGroupToRepo(String taskGroup) {
        return taskGroup.substring(7);
    }
}
