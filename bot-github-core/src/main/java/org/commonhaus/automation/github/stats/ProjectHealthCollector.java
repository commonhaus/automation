package org.commonhaus.automation.github.stats;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import jakarta.enterprise.context.ApplicationScoped;

import org.commonhaus.automation.github.context.DataRepository;
import org.commonhaus.automation.github.context.GitHubQueryContext;

@ApplicationScoped
public class ProjectHealthCollector {

    public ProjectHealthReport collect(GitHubQueryContext qc, LocalDate start) {
        var now = Instant.now();
        var thisSunday = startOfWeekSunday(start.atStartOfDay(ZoneOffset.UTC).toLocalDate());
        var nextSunday = thisSunday.plusDays(7);
        boolean isLive = isBetween(now, thisSunday, nextSunday);
        var repo = qc.getRepository();

        ProjectHealthReport report = new ProjectHealthReport(repo, thisSunday, now, isLive);
        report.statistics = DataRepository.collectStatistics(qc, repo, thisSunday, nextSunday);

        if (isLive) {
            report.snapshot = DataRepository.collectLiveStatistics(qc, repo);
        }
        return report;
    }

    private LocalDate startOfWeekSunday(LocalDate date) {
        return date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.SUNDAY));
    }

    private static boolean isBetween(Instant d, LocalDate start, LocalDate end) {
        return d != null && !d.isBefore(start.atStartOfDay().toInstant(ZoneOffset.UTC))
                && d.isBefore(end.atStartOfDay().toInstant(ZoneOffset.UTC));
    }
}
