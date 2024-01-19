package org.commonhaus.automation.github;

import java.io.IOException;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import org.commonhaus.automation.github.CFGHQueryHelper.RepoQuery;
import org.commonhaus.automation.github.model.Discussion;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkiverse.githubapp.runtime.error.ErrorHandlerBridgeFunction;
import io.quarkiverse.githubapp.runtime.github.GitHubService;
import io.quarkus.logging.Log;

@Singleton
public class WebHookEventListener {
    @Inject
    protected GitHubService gitHubService;

    @Inject
    protected CFGHApp cfgApp;

    public void onEvent(@Observes GitHubEvent event) throws IOException {
        // ... implementation
        Log.debugf("Event: %s (%s)",
                event.getEventAction(),
                event.getRepository().orElse("undefined"));

        long installationId = event.getInstallationId();
        GitHub github = gitHubService.getInstallationClient(installationId);

        try {
            switch (event.getEvent()) {
                case "discussion" -> handleDiscussionEvent(github, event);
                case "discussion_comment" -> handleDiscussionCommentEvent(github, event);
            }
        } catch (Throwable t) {
            new ErrorHandlerBridgeFunction(event).apply(t);
        }
    }

    void handleDiscussionCommentEvent(GitHub github, GitHubEvent ghEvent) throws IOException {
        WebHookDiscussionComment whEvent = WebHookDiscussionComment.from(github,
                ghEvent.getAction(),
                unwrap(ghEvent.getPayload()));
        Log.debugf("Comment %s on Discussion#%s %s by sender %s / author %s",
                whEvent.comment.id,
                whEvent.discussion.number,
                ghEvent.getAction(),
                whEvent.sender,
                whEvent.comment.author);
        // TODO: Enabled on Repo
        // TODO: Permissions check
    }

    void handleDiscussionEvent(GitHub github, GitHubEvent ghEvent) throws IOException {
        WebHookDiscussion whEvent = WebHookDiscussion.from(github,
                ghEvent.getAction(),
                unwrap(ghEvent.getPayload()));

        Log.debugf("Discussion#%s %s by sender %s / author %s / editor %s",
                whEvent.discussion.number,
                ghEvent.getAction(),
                whEvent.sender,
                whEvent.discussion.author,
                whEvent.discussion.editor);

        if (whEvent.type == WebHookDiscussion.Type.created) {
            RepoQuery queryContext = cfgApp.getRepoQueryContext(whEvent.repository, whEvent.installation);
            Discussion.addComment(queryContext, whEvent.discussion, "I see you");
            return;
        }
        // TODO: Enabled on Repo
        // TODO: Permissions check
    }

    private JsonObject unwrap(String payload) {
        JsonReader reader = Json.createReader(new java.io.StringReader(payload));
        return reader.readObject();
    }
}
