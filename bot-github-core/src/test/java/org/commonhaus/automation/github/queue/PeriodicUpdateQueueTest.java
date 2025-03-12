package org.commonhaus.automation.github.queue;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.atomic.AtomicInteger;

import org.commonhaus.automation.github.context.ContextHelper;
import org.commonhaus.automation.github.context.TestRuntimeException;
import org.junit.jupiter.api.Test;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class PeriodicUpdateQueueTest extends ContextHelper {

    private void doStuff(String taskGroup, AtomicInteger counter) {
        counter.incrementAndGet();
        System.out.println(taskGroup + " is doing stuff");
    }

    @Test
    void testReconciliationDeduplication() {
        AtomicInteger changeCounter = new AtomicInteger(0);
        AtomicInteger reconcileCounter = new AtomicInteger(0);
        AtomicInteger otherChange = new AtomicInteger(0);
        AtomicInteger otherReconcile = new AtomicInteger(0);

        updateQueue.queue("testGroup", () -> this.doStuff("testGroup", changeCounter));
        updateQueue.queueReconciliation("testGroup", () -> this.doStuff("testGroup", reconcileCounter));
        updateQueue.queueReconciliation("testGroup", () -> this.doStuff("testGroup", reconcileCounter));
        updateQueue.queue("testGroup", () -> this.doStuff("testGroup", changeCounter));
        updateQueue.queue("otherChange", () -> this.doStuff("otherChange", otherChange));
        updateQueue.queue("testGroup", () -> this.doStuff("testGroup", changeCounter));
        updateQueue.queueReconciliation("testGroup", () -> this.doStuff("testGroup", reconcileCounter));
        updateQueue.queue("otherChange", () -> {
            otherChange.incrementAndGet();
            updateQueue.queueReconciliation("otherChange", () -> this.doStuff("otherChange", otherReconcile));
        });

        // Wait for processing to complete
        await().atMost(5, SECONDS).until(() -> otherChange.get() == 2);
        await().atMost(5, SECONDS).until(() -> otherReconcile.get() == 1);

        // Verify change task ran, and only one reconciliation task
        assertThat(changeCounter.get()).isEqualTo(3);
        // How many times it is reduced will vary, but it should always be < the number of times called.
        assertThat(reconcileCounter.get()).isLessThan(3);
    }

    @Test
    void testNoStarvationOfReconciliationTasks() {
        AtomicInteger busyGroupChanges = new AtomicInteger(0);
        AtomicInteger quietGroupReconcile = new AtomicInteger(0);

        // Queue a reconciliation task for the quiet group
        updateQueue.queueReconciliation("quietGroup", quietGroupReconcile::incrementAndGet);

        // Keep adding changes to the busy group in a separate thread
        Thread changeAdder = new Thread(() -> {
            for (int i = 0; i < 20; i++) {
                updateQueue.queue("busyGroup", busyGroupChanges::incrementAndGet);
                try {
                    Thread.sleep(50); // Slight delay to ensure tasks get queued
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        changeAdder.start();

        // Verify the quiet group's reconciliation still completes
        await().atMost(5, SECONDS).until(() -> quietGroupReconcile.get() > 0);

        // Cleanup
        try {
            changeAdder.join(1000);
        } catch (InterruptedException e) {
            changeAdder.interrupt();
        }
    }

    @Test
    void testResilienceAgainstBadlyBehavedTasks() {
        AtomicInteger goodTaskCounter = new AtomicInteger(0);
        AtomicInteger badTaskExceptions = new AtomicInteger(0);
        AtomicInteger tasksAfterFailure = new AtomicInteger(0);

        // Queue a normal task before the bad one
        updateQueue.queue("goodGroup", goodTaskCounter::incrementAndGet);

        // Queue a task that will throw an exception
        updateQueue.queue("badGroup", () -> {
            badTaskExceptions.incrementAndGet();
            throw new TestRuntimeException("Deliberately failing task");
        });

        // Queue some tasks after the bad one
        for (int i = 0; i < 3; i++) {
            updateQueue.queue("afterFailure", tasksAfterFailure::incrementAndGet);
        }

        // Set up an error handler that counts exceptions
        // This requires modifying your PeriodicUpdateQueue to make the exception handler testable
        // For example, by allowing injection of a custom error handler in tests

        // Wait for good tasks to complete
        await().atMost(5, SECONDS).until(() -> goodTaskCounter.get() > 0);
        await().atMost(5, SECONDS).until(() -> tasksAfterFailure.get() == 3);

        // Verify that tasks continued processing after the failure
        assertThat(goodTaskCounter.get()).isEqualTo(1);
        assertThat(badTaskExceptions.get()).isEqualTo(1);
        assertThat(tasksAfterFailure.get()).isEqualTo(3);

        // bot-github-core/src/test/resources/application.properties
        // mail sent on a separate/unrelated thread
        await().atMost(10, SECONDS).until(() -> mailbox.getTotalMessagesSent() >= 1);
        assertThat(mailbox.getMailsSentTo("bot-error@commonhaus.org")).hasSize(1);
    }
}
