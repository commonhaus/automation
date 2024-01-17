package org.commonhaus.automation.github;

import java.io.IOException;

import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkiverse.githubapp.runtime.github.GitHubService;
import io.quarkus.logging.Log;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
public class EventListener {
    @Inject
    protected GitHubService gitHubService;

    public void onEvent(@Observes GitHubEvent event) throws IOException {
        // ... implementation
        Log.debugf("Event: %s (%s)", 
                event.getEventAction(),
                event.getRepository().orElse("undefined"));
        
        switch(event.getEvent()) {
            case "discussion" -> handleDiscussionEvent(event);
            case "discussion_comment" -> handleDiscussionCommentEvent(event);
        }
    }

    void handleDiscussionCommentEvent(GitHubEvent event) {
        // TODO: Enabled on Repo
        // TODO: Permissions check
        
    }

    void handleDiscussionEvent(GitHubEvent event) throws IOException {
    }
}
