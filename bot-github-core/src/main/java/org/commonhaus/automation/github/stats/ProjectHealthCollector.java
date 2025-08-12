package org.commonhaus.automation.github.stats;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import org.commonhaus.automation.github.context.DataRepository;
import org.commonhaus.automation.github.context.DataRepository.WeeklyStatisticsBuilder;
import org.commonhaus.automation.github.context.GitHubQueryContext;

@ApplicationScoped
public class ProjectHealthCollector {

    /**
     * Potentially expensive operation.
     * Manual only.
     *
     * @param qc
     * @return
     */
    public List<ProjectHealthReport> createHistory(GitHubQueryContext qc) {
        var repo = qc.getRepository();
        Map<LocalDate, WeeklyStatisticsBuilder> history = new HashMap<>();

        List<Instant> startgazerHistory = DataRepository.starHistory(qc, repo);
        for (Instant start : startgazerHistory) {
            LocalDate weekStart = start.atZone(ZoneOffset.UTC).toLocalDate();
            history.computeIfAbsent(weekStart, k -> {
                var report = collect(qc, weekStart, false, false);
                return new WeeklyStatisticsBuilder(weekStart, report.statistics);
            }).addStar();
        }

        List<Instant> releaseHistory = DataRepository.releaseHistory(qc, repo);
        releaseHistory.stream()
                .map(instant -> startOfWeekSunday(instant.atZone(ZoneOffset.UTC).toLocalDate()))
                .forEach(weekStart -> {
                    history.computeIfAbsent(weekStart, k -> {
                        var report = collect(qc, weekStart, false, false);
                        return new WeeklyStatisticsBuilder(weekStart, report.statistics);
                    }).addRelease();
                });

        return history.entrySet().stream()
                .map(entry -> {
                    WeeklyStatisticsBuilder builder = entry.getValue();
                    return new ProjectHealthReport(repo, builder);
                })
                .toList();
    }

    public ProjectHealthReport collect(GitHubQueryContext qc, LocalDate start,
            boolean includeStars, boolean includeReleases) {
        var now = Instant.now();
        var thisSunday = startOfWeekSunday(start.atStartOfDay(ZoneOffset.UTC).toLocalDate());
        var nextSunday = thisSunday.plusDays(7);
        var repo = qc.getRepository();

        ProjectHealthReport report = new ProjectHealthReport(repo, thisSunday, now);
        report.statistics = DataRepository.collectStatistics(qc, repo, thisSunday, nextSunday, includeStars,
                includeReleases);
        return report;
    }

    private LocalDate startOfWeekSunday(LocalDate date) {
        return date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.SUNDAY));
    }
}
