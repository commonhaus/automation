package org.commonhaus.automation.github.watchers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.commonhaus.automation.github.watchers.FileWatcher.FilePushEvent;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkiverse.githubapp.event.Push;

/**
 * GitHub App will transform this into a multiplexed bean for
 * event handling...
 */
@ApplicationScoped
public class PushEventHandler {

    @Inject
    FileWatcher fileWatcher;

    public void handlePushEvent(GitHubEvent event, GitHub github,
            @Push GHEventPayload.Push pushEvent) {
        GHRepository repo = pushEvent.getRepository();
        FilePushEvent fileEvent = new FileWatcher.FilePushEvent(pushEvent, repo, github);
        fileWatcher.handleEvent(fileEvent);
    }
}
