package org.commonhaus.automation.github;

import java.io.IOException;

import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkiverse.githubapp.runtime.github.GitHubService;
import io.quarkus.logging.Log;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

@Singleton
public class WebHookEventListener {
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

    void handleDiscussionCommentEvent(GitHubEvent ghEvent) {
        try {
            WebHookDiscussionComment whEvent = WebHookDiscussionComment.from(
                    ghEvent.getEventAction(), 
                    unwrap(ghEvent.getPayload()));
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        // TODO: Enabled on Repo
        // TODO: Permissions check
        
    }

    void handleDiscussionEvent(GitHubEvent ghEvent) {
        try {
            WebHookDiscussion whEvent = WebHookDiscussion.from(
                    ghEvent.getEventAction(), 
                    unwrap(ghEvent.getPayload()));
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        // TODO: Enabled on Repo
        // TODO: Permissions check
    }

    private JsonObject unwrap(String payload) {
        JsonReader reader = Json.createReader(new java.io.StringReader(payload));
        return reader.readObject();
    }
}
