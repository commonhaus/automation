package org.commonhaus.automation.github.queue;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.mail.LogMailer;

import io.quarkus.logging.Log;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

/**
 * A simple queue to space out / slow down the queries we make to the GitHub API.
 * <p>
 * This queue can be used to interleave events received from the GitHub API with
 * periodic/scheduled queries.
 */
@ApplicationScoped
public class PeriodicUpdateQueue {

    public static final Runnable NOOP = () -> {
    };

    public static final String CONFIG = "config";

    /**
     * Task type.
     * <p>
     * Allows deferral of some types of tasks until others are completed.
     * <p>
     * Example: Initial configuration of repositories - wait until all configuration files
     * are read for discovered repositories before attempting to reconcile/process the configuration.
     * If a change arrives while the reconciliation is in progress, processing those additional
     * changes will be deferred until the reconciliation is complete.
     *
     * Example: Counting votes -- aggregate inbound update events
     * (new comment(s) with results and PR or discussion state change).
     * Then count. Additional changes to the same item will be deferred until the count is complete,
     * but any changes that occur in the interim would then be processed together before triggering
     * a recount.
     */
    public enum TaskType {
        CHANGE,
        RECONCILE
    }

    public record Task(TaskType type, String name, Runnable task) {
    }

    @Inject
    LogMailer logMailer;

    // This is a primitive Queue whose primary purpose is to space
    // out / slow down the queries we make to the GitHub API.

    private final BlockingQueue<Task> taskQueue = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    void startup(@Observes StartupEvent startup) {
        Log.debugf("Starting PeriodicUpdateQueue");
        int initialDelay = LaunchMode.current() == LaunchMode.TEST
                ? 1
                : 15;
        int period = LaunchMode.current() == LaunchMode.TEST
                ? 1
                : 5;
        TimeUnit unit = LaunchMode.current() == LaunchMode.TEST
                ? TimeUnit.MILLISECONDS
                : TimeUnit.SECONDS;
        // Don't flood. Be leisurely for scheduled/cron queries
        executor.scheduleAtFixedRate(() -> {
            Task task = taskQueue.poll();
            if (task != null) {
                run(task);
            }
        }, initialDelay, period, unit);
    }

    void shutdown(@Observes ShutdownEvent shutdown) {
        executor.shutdown();
    }

    public void queue(Runnable task) {
        this.queue(CONFIG, task);
    }

    public void queue(String name, Runnable task) {
        Log.debugf("QUEUE task %s", name);
        taskQueue.add(new Task(TaskType.CHANGE, name, task));
    }

    public void queueReconciliation(String name, Runnable task) {
        Log.debugf("QUEUE reconciliation %s", name);
        taskQueue.add(new Task(TaskType.RECONCILE, name, task));
    }

    private void run(Task task) {
        Task next = taskQueue.peek();

        if (task.type() == TaskType.RECONCILE) {
            // Defer reconciliation if changes of the same group are pending
            if (next != null && next.name().equals(task.name())) {
                if (next.type() == TaskType.CHANGE) {
                    taskQueue.add(task); // Re-queue this reconciliation for later
                    Log.debugf("RECONCILE %s task postponed for same-group changes", task.name());
                } else {
                    Log.debugf("RECONCILE %s task skipped (duplicate next)", task.name());
                }
                return;
            }
        }

        try {
            // Execute the main task
            task.task().run();
        } catch (Exception e) {
            logMailer.logAndSendEmail("queue",
                    "Error running %s %s task".formatted(task.type(), task.name()),
                    e, logMailer.botErrorEmailAddress());
        }

        Log.debugf("%s %s task completed; %s remaining", task.type(), task.name(), taskQueue.size());
    }
}
