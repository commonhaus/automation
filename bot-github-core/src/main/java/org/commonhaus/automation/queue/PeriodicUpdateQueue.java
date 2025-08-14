package org.commonhaus.automation.queue;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.commonhaus.automation.config.BotConfig;
import org.commonhaus.automation.mail.LogMailer;

import io.quarkus.logging.Log;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;

/**
 * A simple queue to space out / slow down the queries we make to the GitHub API.
 * <p>
 * This queue can be used to interleave events received from the GitHub API with
 * periodic/scheduled queries.
 */
@Singleton
public class PeriodicUpdateQueue {
    public static final String CONFIG = "config";

    public static final Runnable NOOP = () -> {
    };

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

    @Inject
    BotConfig botConfig;

    @Inject
    LogMailer logMailer;

    // This is a primitive Queue whose primary purpose is to space
    // out / slow down the queries we make to the GitHub API.

    private final BlockingQueue<Task> taskQueue = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    /** Retry tasks: tasks that failed due to network or authentication issues that should be retried */
    private final Map<String, RetryTask> retryTasks = new ConcurrentHashMap<>();

    /** Background tasks: low priority; choose after main tasks quiesce */
    private final Map<String, Runnable> backgroundTasks = new ConcurrentHashMap<>();

    /** Pending reconcile tasks (by group) */
    private final Map<String, AtomicInteger> reconcileCounters = new ConcurrentHashMap<>();

    void startup(@Observes StartupEvent startup) {
        Log.debugf("🧵 Starting PeriodicUpdateQueue");

        long initialDelay = botConfig.queue().initialDelay().toMillis();
        long period = botConfig.queue().period().toMillis();

        // Don't flood. Plod along for interactions with GH API
        executor.scheduleAtFixedRate(this::runTask,
                initialDelay,
                period,
                TimeUnit.MILLISECONDS);
    }

    void shutdown(@Observes ShutdownEvent shutdown) {
        executor.shutdown();
    }

    public void queue(String name, Runnable task) {
        Log.debugf("🧵 ❇️ CHANGE task %s", name);
        taskQueue.add(new Task(TaskType.CHANGE, name, task));
    }

    public void queueReconciliation(String name, Runnable task) {
        Log.debugf("🧵 ❇️ RECONCILE task %s", name);
        reconcileCounters.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
        taskQueue.add(new Task(TaskType.RECONCILE, name, task));
    }

    public void queueBackground(String name, Runnable task) {
        Log.debugf("🧵 ❇️ BACKGROUND task %s", name);
        backgroundTasks.put(name, task);
    }

    /**
     * Schedule a reconciliation event.
     * <p>
     * The caller should check the retry count and decide whether to schedule another retry.
     *
     * @param name Task group
     * @param retryRunnable Retry runnable; takes the retry count as an argument
     * @param retryCount Previous retry count (0 for initial attempt)
     */
    public void scheduleReconciliationRetry(String name, Consumer<Integer> retryRunnable, int retryCount) {
        Log.debugf("🧵 ❇️ SCHEDULE task %s", name);
        retryTasks.putIfAbsent(name, new RetryTask(name, retryRunnable, retryCount));
    }

    private void runTask() {
        Task task = taskQueue.poll();
        if (task != null) {
            // Remove from background tasks if same group exists
            backgroundTasks.remove(task.name());
            run(task);
        } else if (!backgroundTasks.isEmpty()) {
            runBackgroundTask();
        }
    }

    private void run(Task task) {
        try {
            boolean tryNext;
            do {
                // skip or collapse reconciliation task?
                tryNext = false;

                // Defer reconciliation if changes of the same group are pending
                if (task.type() == TaskType.RECONCILE) {
                    int pendingCount = reconcileCounters.getOrDefault(task.name(), new AtomicInteger()).decrementAndGet();
                    if (pendingCount > 0) {
                        // There is another pending reconciliation for the same group; skip this one
                        Log.debugf("🧵 ❎ RECONCILE [skip] %s task; %s of this task remaining", task.name(), pendingCount);
                        task = taskQueue.poll(); // Get the next task
                        tryNext = true;
                    } else {
                        reconcileCounters.remove(task.name());
                    }
                }
            } while (tryNext);

            // Execute the task
            Log.debugf("🧵 ➡️ %s %s task; %s tasks remaining", task.type(), task.name(), taskQueue.size());
            task.task().run();
            Log.debugf("🧵 ⬅️ %s %s task; %s tasks remaining", task.type(), task.name(), taskQueue.size());
        } catch (Throwable e) {
            logMailer.logAndSendEmail("queue",
                    "🧵 Error running %s %s task".formatted(task.type(), task.name()),
                    e, logMailer.botErrorEmailAddress());
        }
    }

    private void runBackgroundTask() {
        Iterator<Map.Entry<String, Runnable>> iterator = backgroundTasks.entrySet().iterator();
        if (iterator.hasNext()) {
            // we don't actually care about the order. Just grab one.
            var entry = iterator.next();
            iterator.remove();

            String taskName = entry.getKey();
            Runnable bgTask = entry.getValue();
            Log.debugf("🧵 BACKGROUND [begin] %s task", taskName);
            try {
                bgTask.run();
            } catch (Throwable e) {
                logMailer.logAndSendEmail("queue",
                        "🧵 Error running BACKGROUND %s task".formatted(taskName),
                        e, logMailer.botErrorEmailAddress());
            }
            Log.debugf("🧵 BACKGROUND [end] %s task", taskName);
        }
    }

    /**
     * Requeue retriable tasks
     */
    @Scheduled(every = "30s")
    public void processRetries() {
        // Use an iterator to safely remove while iterating
        Iterator<Map.Entry<String, RetryTask>> iterator = retryTasks.entrySet().iterator();
        while (iterator.hasNext()) {
            RetryTask retryTask = iterator.next().getValue();
            if (retryTask.isReady()) {
                iterator.remove();
                Log.debugf("🧵 RETRY %s", retryTask.name);
                taskQueue.add(new Task(TaskType.RECONCILE, retryTask.name, retryTask));
            }
        }
    }

    public boolean isEmpty() {
        return taskQueue.isEmpty() && retryTasks.isEmpty();
    }

    public String toString() {
        return "PeriodicUpdateQueue(%s :: %s)".formatted(taskQueue.size(), retryTasks.size());
    }

    public record Task(TaskType type, String name, Runnable task) {
    }

    /**
     * A task that can be retried after a delay.
     *
     * The GitHub SDK already performs automatic retries for transient errors,
     * but this is useful for tasks beyond that scope.
     *
     * @see {@link org.kohsuke.github.GitHubClient#sendRequest(org.kohsuke.github.GitHubRequest, org.kohsuke.github.GitHubClient.BodyHandler)}
     */
    public static class RetryTask implements Runnable {
        final String name;
        final Consumer<Integer> task;
        final int retryCount;
        final long nextRetryTime;

        private RetryTask(String taskGroup, Consumer<Integer> retryRunnable, int retryCount) {
            this.name = taskGroup;
            this.task = retryRunnable;
            this.retryCount = retryCount + 1;

            // GitHub client only retries twice with 100ms delays
            // Our strategy should start after those quick retries would have failed
            // First retry: 5 seconds
            // Second retry: 30 seconds
            // Third retry: 2 minutes
            // Fourth retry: 10 minutes
            // Fifth+ retry: 30 minutes
            long delayMs = LaunchMode.TEST == LaunchMode.current()
                    ? 5 // tiny delay for tests
                    : switch (retryCount) {
                        case 0 -> 5_000; // 5 seconds
                        case 1 -> 30_000; // 30 seconds
                        case 2 -> 120_000; // 2 minutes
                        case 3 -> 600_000; // 10 minutes
                        default -> 1_800_000; // 30 minutes
                    };

            this.nextRetryTime = System.currentTimeMillis() + delayMs;
        }

        private boolean isReady() {
            return System.currentTimeMillis() >= nextRetryTime;
        }

        @Override
        public void run() {
            task.accept(retryCount);
        }
    }
}
