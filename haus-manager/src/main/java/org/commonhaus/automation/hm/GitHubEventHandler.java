package org.commonhaus.automation.hm;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
public class GitHubEventHandler {

    public static record FileEvent(
            GHEventPayload.Push pushEvent,
            GHRepository repository,
            GitHub github) {
    }

    @Inject
    FileWatcher monitoredFileEvents;

    public void handlePushEvent(GitHubEvent event, GitHub github,
            @Push GHEventPayload.Push pushEvent) {
        GHRepository repo = pushEvent.getRepository();
        FileEvent fileEvent = new FileEvent(pushEvent, repo, github);
        monitoredFileEvents.handleEvent(fileEvent);
    }
}
