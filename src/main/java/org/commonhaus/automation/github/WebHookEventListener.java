package org.commonhaus.automation.github;

import java.io.IOException;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import org.kohsuke.github.GHMyself;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.GitHubEvent;
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

        switch (event.getEvent()) {
            case "discussion" -> handleDiscussionEvent(github, event);
            case "discussion_comment" -> handleDiscussionCommentEvent(github, event);
        }
    }

    void handleDiscussionCommentEvent(GitHub github, GitHubEvent ghEvent) {
        try {
            WebHookDiscussionComment whEvent = WebHookDiscussionComment.from(github,
                    ghEvent.getAction(),
                    unwrap(ghEvent.getPayload()));
            Log.debug(whEvent);
            // TODO: Enabled on Repo
            // TODO: Permissions check
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    void handleDiscussionEvent(GitHub github, GitHubEvent ghEvent) {
        try {
            WebHookDiscussion whEvent = WebHookDiscussion.from(github,
                    ghEvent.getAction(),
                    unwrap(ghEvent.getPayload()));
            Log.debugf("%s; %s", whEvent);
            if (whEvent.type == WebHookDiscussion.Type.created) {
                CFGHQueryHelper queryContext = cfgApp.getQueryContext(whEvent.repository, whEvent.installation);

                GHMyself myself = github.getMyself();
                boolean iAmAuthor = myself.getLogin().equals(whEvent.discussion.author.login);
                Log.debugf("Discussion created by %s (%s)", whEvent.discussion.author, iAmAuthor);

                Discussion.addComment(queryContext, whEvent.discussion, "I see you");
                return;
            }
            // TODO: Enabled on Repo
            // TODO: Permissions check

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private JsonObject unwrap(String payload) {
        JsonReader reader = Json.createReader(new java.io.StringReader(payload));
        return reader.readObject();
    }
}
