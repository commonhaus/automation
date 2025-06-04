package org.commonhaus.automation.github.stats;

import java.time.Instant;
import java.time.LocalDate;

import org.commonhaus.automation.github.context.DataRepository.WeeklyStatistics;
import org.commonhaus.automation.github.context.DataRepository.WeeklyStatisticsBuilder;
import org.kohsuke.github.GHRepository;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class ProjectHealthReport {
    final String organization;
    final String repository;
    final LocalDate weekOf;
    final Instant collected;
    public WeeklyStatistics statistics;

    ProjectHealthReport(GHRepository repository, LocalDate weekOf, Instant collected) {
        this.organization = repository.getOwnerName();
        this.repository = repository.getName();
        this.weekOf = weekOf;
        this.collected = collected;
    }

    public ProjectHealthReport(GHRepository repo, WeeklyStatisticsBuilder builder) {
        this.collected = Instant.now();

        this.organization = repo.getOwnerName();
        this.repository = repo.getName();
        this.weekOf = builder.weekStart;

        this.statistics = builder.build();
    }
}
