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
     * @param orgQc
     * @return
     */
    public List<ProjectHealthReport> createHistory(GitHubQueryContext orgQc, String repoFullName) {
        var repo = orgQc.getRepository(repoFullName);
        Map<LocalDate, WeeklyStatisticsBuilder> history = new HashMap<>();

        DataRepository.itemHistory(orgQc, repo, history);

        List<Instant> startgazerHistory = DataRepository.starHistory(orgQc, repo);
        for (Instant start : startgazerHistory) {
            LocalDate weekStart = start.atZone(ZoneOffset.UTC).toLocalDate();
            history.computeIfAbsent(weekStart, k -> {
                return new WeeklyStatisticsBuilder(weekStart);
            }).addStar();
        }

        List<Instant> releaseHistory = DataRepository.releaseHistory(orgQc, repo);
        releaseHistory.stream()
                .map(instant -> startOfWeekSunday(instant.atZone(ZoneOffset.UTC).toLocalDate()))
                .forEach(weekStart -> {
                    history.computeIfAbsent(weekStart, k -> {
                        return new WeeklyStatisticsBuilder(weekStart);
                    }).addRelease();
                });

        return history.values().stream()
                .map(builder -> new ProjectHealthReport(repo, builder))
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
