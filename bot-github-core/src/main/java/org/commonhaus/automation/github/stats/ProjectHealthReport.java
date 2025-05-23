package org.commonhaus.automation.github.stats;

import java.time.Instant;
import java.time.LocalDate;

import org.commonhaus.automation.github.context.DataRepository.ActivitySnapshot;
import org.commonhaus.automation.github.context.DataRepository.ItemStatistics;
import org.kohsuke.github.GHRepository;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class ProjectHealthReport {
    final String organization;
    final String repository;
    final LocalDate weekOf;
    final Instant collected;
    final boolean isLive;
    public ItemStatistics statistics;
    public ActivitySnapshot snapshot;

    ProjectHealthReport(GHRepository repository, LocalDate weekOf, Instant collected, boolean isLive) {
        this.organization = repository.getOwnerName();
        this.repository = repository.getName();
        this.weekOf = weekOf;
        this.collected = collected;
        this.isLive = isLive;
    }
}
