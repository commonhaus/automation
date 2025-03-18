package org.commonhaus.automation.github.watchers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.commonhaus.automation.github.queue.PeriodicUpdateQueue;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHEventPayload.Push.PushCommit;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;

import io.quarkus.logging.Log;

@ApplicationScoped
public class FileWatcher {
    static final String ME = "fileWatcher";

    final Map<String, WatchedFiles> repositoryFiles = new ConcurrentHashMap<>();

    @Inject
    PeriodicUpdateQueue updateQueue;

    /**
     * Watch for repository discovery events and clean up watchers
     * if repositories or installations (association between GH App and an Organization)
     * are removed
     *
     * @param repoEvent
     */
    protected void onRepositoryDiscovery(@Observes @Priority(value = 5) RepositoryDiscoveryEvent repoEvent) {
        if (repoEvent.removed()) {
            if (repoEvent.installation()) {
                // If an entire installation is removed, clean up all watchers for that installation
                long installationId = repoEvent.installationId();
                repositoryFiles.entrySet().removeIf(entry -> entry.getValue().installationId == installationId);
                Log.debugf("%s: cleared watchers for installation %d", ME, installationId);
            } else {
                // Otherwise just remove watchers for the specific repository
                String repoFullName = repoEvent.repository().getFullName();
                repositoryFiles.remove(repoFullName);
                Log.debugf("%s: cleared watchers for repository %s", ME, repoFullName);
            }
        }
    }

    /**
     * Create a new file watcher for the specified repository and file.
     * When the file is updated, the callback will be invoked.
     * <p>
     * Registered watchers will be automatically cleaned up if app loses visiblity to the
     * repository or organization (DiscoveryAction: REMOVED, INSTALL_REMOVED)
     *
     *
     * @param taskGroupName Name of the task group to use for periodic updates
     * @param installationId Installation ID for the repository
     * @param repoName Name of the repository
     * @param fileName Name of the file to watch
     * @param callback Callback function to invoke when the file is updated (with FileUpdate object)
     */
    public void watchFile(String taskGroupName, long installationId, String repoName, String fileName,
            Consumer<FileUpdate> callback) {
        repositoryFiles.computeIfAbsent(repoName, k -> new WatchedFiles(repoName, installationId))
                .add(fileName, new TaskCallback<FileUpdate>(taskGroupName, callback));
    }

    public void unwatchAll(String taskGroup) {
        for (var entry : repositoryFiles.entrySet()) {
            entry.getValue().filesByPath.values().removeIf(callbacks -> {
                callbacks.removeIf(callback -> callback.taskGroupName().equals(taskGroup));
                return callbacks.isEmpty();
            });
        }
        repositoryFiles.values().removeIf(x -> x.filesByPath.isEmpty());
    }

    public void handleEvent(FilePushEvent fileEvent) {
        GHRepository repo = fileEvent.repository();
        GHEventPayload.Push pushEvent = fileEvent.pushEvent();
        WatchedFiles watcher = repositoryFiles.get(repo.getFullName());

        // Only watch the main branch of repositories we are monitoring
        if (watcher == null || !pushEvent.getRef().equals("refs/heads/main")) {
            return;
        }

        Log.debugf("[%s-%s] push event in %s; %s commit(s)", ME,
                fileEvent.installationId(), repo.getFullName(), pushEvent.getCommits().size());

        watcher.handlePush(fileEvent, updateQueue);
    }

    static class WatchedFiles {
        final String repoFullName;
        final long installationId;
        final Map<String, Set<TaskCallback<FileUpdate>>> filesByPath = new ConcurrentHashMap<>();

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
        public void add(String filePath, TaskCallback<FileUpdate> callback) {
            filesByPath.computeIfAbsent(filePath, k -> ConcurrentHashMap.newKeySet())
                    .add(callback);
        }

        /**
         * See if the Push Event touched any interesting files.
         * If it did, queue invocation of the callback
         *
         * @param event FileEvent (PushEvent, GHRepository, GitHub)
         * @param periodicSync Queue for periodic events and updates
         */
        public void handlePush(FilePushEvent event, PeriodicUpdateQueue periodicSync) {
            // Sort by timestamp ascending (oldest first)
            var pushEvent = event.pushEvent();
            var commits = new ArrayList<>(pushEvent.getCommits());
            commits.sort(Comparator.comparing(commit -> commit.getTimestamp()));

            for (var entry : filesByPath.entrySet()) {
                Set<TaskCallback<FileUpdate>> callbacks = entry.getValue();
                FileUpdateType updateType = commitsContain(commits, entry.getKey());
                if (updateType != null) {
                    FileUpdate update = new FileUpdate(entry.getKey(), updateType, event);
                    for (var callback : callbacks) {
                        // Found an interesting file in the push event, queue an update
                        periodicSync.queue(callback.taskGroupName(),
                                () -> callback.run(update));
                    }
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
        FileUpdateType commitsContain(List<PushCommit> commits, String path) {
            FileUpdateType updateType = null;
            for (var commit : commits) {
                // Last one wins.
                if (commit.getAdded().contains(path)) {
                    updateType = FileUpdateType.ADDED;
                } else if (commit.getRemoved().contains(path)) {
                    updateType = FileUpdateType.REMOVED;
                } else if (commit.getModified().contains(path)) {
                    updateType = FileUpdateType.MODIFIED;
                }
            }
            return updateType;
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

    public boolean isWatching(String repoName) {
        return repositoryFiles.containsKey(repoName);
    }

    /**
     * Hard-reset of the file watcher.
     * This is useful for testing.
     */
    protected void reset() {
        repositoryFiles.clear();
    }

    void dumpWatcherState() {
        System.out.println("--------- FileWatcher state ---------");
        for (var entry : repositoryFiles.entrySet()) {
            String repoName = entry.getKey();
            System.out.println("Repo: " + repoName);
            var watcher = entry.getValue();
            System.out.println("  Files watching:");
            for (var fileEntry : watcher.filesByPath.entrySet()) {
                String filePath = fileEntry.getKey();
                int callbackCount = fileEntry.getValue().size();
                System.out.println("    " + filePath + " - " + callbackCount + " callbacks");
            }
        }
        System.out.println("------------------------------------");
    }

    public enum FileUpdateType {
        ADDED,
        MODIFIED,
        REMOVED
    }

    public static record FileUpdate(
            String filePath,
            FileUpdateType updateType,
            long installationId,
            GHRepository repository,
            GitHub github) {

        public FileUpdate(String filePath, FileUpdateType updateType, FilePushEvent pushEvent) {
            this(filePath, updateType, pushEvent.installationId(), pushEvent.repository(), pushEvent.github());
        }
    }

    public static record FilePushEvent(
            GHEventPayload.Push pushEvent,
            long installationId,
            GHRepository repository,
            GHUser sender,
            GitHub github) {
    }
}
