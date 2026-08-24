package org.commonhaus.automation.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import org.commonhaus.automation.ContextService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.core.type.TypeReference;

class TaskStateServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void recordRunQueuesBackgroundPersistenceWhenStateFileConfigured() {
        class TrackingQueue extends PeriodicUpdateQueue {
            String backgroundTaskName;
            Runnable backgroundTask;

            @Override
            public void queueBackground(String name, Runnable task) {
                this.backgroundTaskName = name;
                this.backgroundTask = task;
            }
        }

        TrackingQueue updateQueue = new TrackingQueue();
        TaskStateService taskState = new TaskStateService();
        taskState.updateQueue = updateQueue;
        taskState.configureStateFile(tempDir.resolve("task-state.yml"));

        taskState.recordRun("test-task");

        assertThat(updateQueue.backgroundTaskName).isEqualTo(TaskStateService.BACKGROUND_PERSIST_TASK);
        assertThat(updateQueue.backgroundTask).isNotNull();
    }

    @Test
    void persistStateWritesRecordedTaskState() throws IOException {
        class TrackingQueue extends PeriodicUpdateQueue {
            @Override
            public void queueBackground(String name, Runnable task) {
            }
        }

        TaskStateService taskState = new TaskStateService();
        Path stateFile = tempDir.resolve("task-state.yml");
        taskState.updateQueue = new TrackingQueue();
        taskState.configureStateFile(stateFile);

        Instant recordedAt = taskState.recordRun("test-task");
        taskState.persistState();

        assertThat(stateFile).exists();
        Map<String, Instant> storedState = ContextService.yamlMapper.readValue(
                Files.readString(stateFile),
                new TypeReference<Map<String, Instant>>() {
                });
        assertThat(storedState).containsEntry("test-task", recordedAt);
    }
}
