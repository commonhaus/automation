package org.commonhaus.automation.github.voting;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class CheckStatus {
    private Instant lastCheck;
    final AtomicBoolean running = new AtomicBoolean(false);

    public CheckStatus() {
    }

    public boolean startScheduledUpdate() {
        // Don't check more than once every 15 minutes
        if (lastCheck != null && lastCheck.plus(15, ChronoUnit.MINUTES).isBefore(Instant.now())) {
            return false;
        }
        return running.compareAndSet(false, true);
    }

    public boolean startUpdate(VoteEvent voteEvent) {
        if (voteEvent.isScheduled()) {
            return startScheduledUpdate();
        }
        return running.compareAndSet(false, true);
    }

    public void finishUpdate() {
        lastCheck = Instant.now();
        running.set(false);
    }
}
