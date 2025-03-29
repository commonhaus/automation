package org.commonhaus.automation.queue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.ContextService;
import org.commonhaus.automation.config.BotConfig;

import com.fasterxml.jackson.core.type.TypeReference;

import io.quarkus.logging.Log;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class TaskStateService {
    private static final String ME = TaskStateService.class.getSimpleName();
    private final static TypeReference<Map<String, Instant>> TYPE_REF = new TypeReference<>() {
    };

    @Inject
    BotConfig botConfig;

    @Inject
    PeriodicUpdateQueue updateQueue;

    private Map<String, Instant> lastRunTimes = new ConcurrentHashMap<>();
    private Path stateFile;

    void init(@Observes StartupEvent event) {
        if (LaunchMode.current() == LaunchMode.TEST) {
            return;
        }
        String filePath = botConfig.queue().stateFilePath().orElse(null);
        if (filePath == null) {
            return;
        }
        Log.infof("[%s] Task state will be persisted to %s", ME, filePath);
        stateFile = Path.of(filePath);

        // Load state from file if it exists
        if (Files.exists(stateFile)) {
            try {
                String content = Files.readString(stateFile);
                Map<String, Instant> loadedState = ContextService.yamlMapper.readValue(content, TYPE_REF);
                lastRunTimes.putAll(loadedState);
                Log.infof("[%s] Loaded task state from %s", ME, stateFile);
            } catch (IOException e) {
                Log.warn("Could not read state file", e);
            }
        }
    }

    private void saveState(@Observes ShutdownEvent event) {
        if (LaunchMode.current() == LaunchMode.TEST) {
            return;
        }
        if (stateFile != null) {
            try {
                Files.writeString(stateFile, ContextService.yamlMapper.writeValueAsString(lastRunTimes));
                Log.infof("[%s] Saved task state to %s", ME, stateFile);
            } catch (IOException e) {
                Log.warn("Could not save state file", e);
            }
        }
    }

    public void recordRun(String taskId) {
        lastRunTimes.put(taskId, Instant.now());
        updateQueue.queueReconciliation(ME, () -> this.saveState(null));
    }

    public boolean shouldRun(String taskId, Duration maxAge) {
        if (LaunchMode.current() == LaunchMode.TEST) {
            return true;
        }
        Instant lastRun = lastRunTimes.get(taskId);
        if (lastRun == null) {
            return true;
        }
        return Duration.between(lastRun, Instant.now()).compareTo(maxAge) > 0;
    }
}
