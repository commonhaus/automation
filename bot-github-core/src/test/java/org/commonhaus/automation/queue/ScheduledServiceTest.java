package org.commonhaus.automation.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.commonhaus.automation.github.discovery.BootstrapDiscoveryEvent;
import org.junit.jupiter.api.Test;

class ScheduledServiceTest {

    @Test
    void bootstrapFinishedDoesNotRecordRunState() {
        class TrackingTaskStateService extends TaskStateService {
            int recordRunCalls = 0;

            @Override
            public Instant lastRunOrNow(String taskId) {
                return Instant.parse("2026-08-24T12:00:00Z");
            }

            @Override
            public Instant recordRun(String taskId) {
                recordRunCalls++;
                return Instant.parse("2026-08-24T15:00:00Z");
            }
        }

        TrackingTaskStateService trackingTaskState = new TrackingTaskStateService();

        ScheduledService scheduledService = new ScheduledService() {
            {
                this.taskState = trackingTaskState;
            }

            @Override
            protected String me() {
                return "test-task";
            }
        };

        scheduledService.bootstrapFinished(new BootstrapDiscoveryEvent(List.of()));

        assertThat(scheduledService.lastRun).isEqualTo("2026-08-24T12:00:00Z");
        assertThat(trackingTaskState.recordRunCalls).isZero();
    }
}
