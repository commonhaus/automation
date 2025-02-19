package org.commonhaus.automation.hm;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import jakarta.enterprise.context.ApplicationScoped;

import org.commonhaus.automation.hm.GitHubEventHandler.FileEvent;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHRepository;

@ApplicationScoped
public class FileWatcher {
    final Map<String, WatchedFiles> repositoryFiles = new HashMap<>();

    final AppContextService appContextService;

    public FileWatcher(AppContextService appContextService) {
        this.appContextService = appContextService;
    }

    public void watchFile(long installationId, String repoName, String fileName,
            Consumer<GitHubEventHandler.FileEvent> callback) {
        repositoryFiles.computeIfAbsent(repoName, k -> new WatchedFiles(repoName, installationId))
                .add(fileName, callback);
    }

    public void handleEvent(FileEvent fileEvent) {
        GHRepository repo = fileEvent.repository();
        GHEventPayload.Push pushEvent = fileEvent.pushEvent();
        WatchedFiles watcher = repositoryFiles.get(repo.getFullName());

        // Only watch the main branch of repositories we are monitoring
        if (watcher == null || !pushEvent.getRef().equals("refs/heads/main")) {
            return;
        }

        watcher.pushUpdate(fileEvent);
    }

    static class WatchedFiles {
        final String repoFullName;
        final long installationId;
        final Map<String, Consumer<GitHubEventHandler.FileEvent>> filesByPath = new HashMap<>();

        public WatchedFiles(String repoFullName, long installationId) {
            this.repoFullName = repoFullName;
            this.installationId = installationId;
        }

        public void add(String fileName, Consumer<GitHubEventHandler.FileEvent> callback) {
            filesByPath.put(fileName, callback);
        }

        public void pushUpdate(GitHubEventHandler.FileEvent event) {
            for (var entry : filesByPath.entrySet()) {
                if (commitsContain(event.pushEvent(), entry.getKey())) {
                    entry.getValue().accept(event);
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
        public boolean commitsContain(GHEventPayload.Push pushEvent, String path) {
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
}
