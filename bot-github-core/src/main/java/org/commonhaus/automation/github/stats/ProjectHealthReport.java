package org.commonhaus.automation.github.stats;

import java.time.Instant;
import java.time.LocalDate;

import org.commonhaus.automation.github.context.DataRepository.WeeklyStatistics;
import org.commonhaus.automation.github.context.DataRepository.WeeklyStatisticsBuilder;
import org.kohsuke.github.GHRepository;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class ProjectHealthReport {
    public final String organization;
    public final String repository;
    public final String fullName;
    public final LocalDate weekOf;
    public final Instant collected;
    public WeeklyStatistics statistics;

    ProjectHealthReport(GHRepository repo, LocalDate weekOf, Instant collected) {
        this.organization = repo.getOwnerName();
        this.repository = repo.getName();
        this.fullName = repo.getFullName();
        this.weekOf = weekOf;
        this.collected = collected;
    }

    public ProjectHealthReport(GHRepository repo, WeeklyStatisticsBuilder builder) {
        this.collected = Instant.now();

        this.organization = repo.getOwnerName();
        this.repository = repo.getName();
        this.fullName = repo.getFullName();
        this.weekOf = builder.weekStart;

        this.statistics = builder.build();
    }
}
