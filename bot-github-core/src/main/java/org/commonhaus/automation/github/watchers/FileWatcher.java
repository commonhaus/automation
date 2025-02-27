package org.commonhaus.automation.github.watchers;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import jakarta.enterprise.context.ApplicationScoped;

import org.commonhaus.automation.github.queue.PeriodicUpdateQueue;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

@ApplicationScoped
public class FileWatcher implements Watcher {
    static final String me = "fileWatcher";

    final Map<String, WatchedFiles> repositoryFiles = new HashMap<>();

    final PeriodicUpdateQueue periodicSync;

    public FileWatcher(PeriodicUpdateQueue periodicSync) {
        this.periodicSync = periodicSync;
    }

    public void watchFile(String taskGroupName, long installationId, String repoName, String fileName,
            Consumer<FileUpdate> callback) {
        repositoryFiles.computeIfAbsent(repoName, k -> new WatchedFiles(repoName, installationId))
                .add(fileName, new TaskCallback(taskGroupName, callback));
    }

    public void handleEvent(FilePushEvent fileEvent) {
        GHRepository repo = fileEvent.repository();
        GHEventPayload.Push pushEvent = fileEvent.pushEvent();
        WatchedFiles watcher = repositoryFiles.get(repo.getFullName());

        // Only watch the main branch of repositories we are monitoring
        if (watcher == null || !pushEvent.getRef().equals("refs/heads/main")) {
            return;
        }

        watcher.handlePush(fileEvent, periodicSync);
    }

    public void refresh() {
        // TODO: revisit all watched files and drive associated callbacks
    }

    static class WatchedFiles {
        final String repoFullName;
        final long installationId;
        final Map<String, TaskCallback> filesByPath = new HashMap<>();

        public WatchedFiles(String repoFullName, long installationId) {
            this.repoFullName = repoFullName;
            this.installationId = installationId;
        }

        /**
         * Add file to list of interesting files to watch
         *
         * @param filePath Path of file to watch
         * @param callback Callback to invoke if this file is changed
         */
        public void add(String fileName, TaskCallback callback) {
            filesByPath.put(fileName, callback);
        }

        /**
         * See if the Push Event touched any interesting files.
         * If it did, queue invocation of the callback
         *
         * @param event FileEvent (PushEvent, GHRepository, GitHub)
         * @param periodicSync Queue for periodic events and updates
         */
        public void handlePush(FilePushEvent event, PeriodicUpdateQueue periodicSync) {
            for (var entry : filesByPath.entrySet()) {
                if (commitsContain(event.pushEvent(), entry.getKey())) {
                    FileUpdate update = new FileUpdate(entry.getKey(), event.repository(), event.github());
                    TaskCallback worker = entry.getValue();

                    // Found an interesting file in the push event, queue an update
                    periodicSync.queue(worker.taskGroupName(),
                            () -> worker.run(update));
                    break;
                }
            }
        }

        /**
         * Event filter: check if the push event contains changes to the specified path
         *
         * @param pushEvent the push event
         * @param path the path to check
         * @return true if the push event contains changes to the path
         */
        boolean commitsContain(GHEventPayload.Push pushEvent, String path) {
            return pushEvent.getCommits().stream()
                    .anyMatch(commit -> commit.getAdded().contains(path)
                            || commit.getModified().contains(path));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof WatchedFiles that))
                return false;
            return installationId == that.installationId && repoFullName.equals(that.repoFullName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(repoFullName, installationId);
        }
    }

    public static record TaskCallback(
            String taskGroupName,
            Consumer<FileUpdate> callback) {

        public void run(FileUpdate update) {
            callback.accept(update);
        }
    }

    public static record FileUpdate(
            String filePath,
            GHRepository repository,
            GitHub github) {
    }

    public static record FilePushEvent(
            GHEventPayload.Push pushEvent,
            GHRepository repository,
            GitHub github) {
    }
}
