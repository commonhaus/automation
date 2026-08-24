package org.commonhaus.automation.queue;

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
        // Bootstrap restores the prior persisted view of this task's freshness.
        // It must not stamp a new run before scheduled or restart-triggered work actually executes.
        lastRun = taskState.lastRunOrNow(me()).toString();
    }
}
