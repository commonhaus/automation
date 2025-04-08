package org.commonhaus.automation.queue;

import java.time.Instant;
import java.util.Optional;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.github.discovery.BootstrapDiscoveryEvent;

public abstract class ScheduledService {
    protected volatile String lastRun = "never";

    @Inject
    protected TaskStateService taskState;

    protected abstract String me();

    protected void recordRun() {
        lastRun = taskState.recordRun(me()).toString();
    }

    protected void bootstrapFinished(@Observes BootstrapDiscoveryEvent event) {
        lastRun = Optional.ofNullable(taskState.lastRun(me()))
                .map(Instant::toString)
                .orElse("never");
        recordRun();
    }
}
