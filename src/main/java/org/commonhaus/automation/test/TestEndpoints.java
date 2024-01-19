package org.commonhaus.automation.test;

import java.io.IOException;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.commonhaus.automation.github.CFGHApp;
import org.commonhaus.automation.github.CFGHQueryHelper.RepoQuery;
import org.commonhaus.automation.github.model.Discussion;
import org.commonhaus.automation.github.model.DiscussionCategory;
import org.commonhaus.automation.github.model.Label;
import org.commonhaus.automation.github.model.Reaction;

import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.logging.Log;
import io.quarkus.vertx.web.Route;
import io.smallrye.common.annotation.Blocking;
import io.vertx.ext.web.RoutingContext;

/**
 * Test-only endpoints to allow us to drive certain
 * non-webhook paths from the browser.
 *
 * Use the GitHub app replay feature to test WebHooks.
 */
@ApplicationScoped
@UnlessBuildProfile("prod")
public class TestEndpoints {

    @Inject
    CFGHApp app;

    @Blocking
    @Route(methods = Route.HttpMethod.GET)
    void setup(RoutingContext rc) throws IOException {
        app.purgeCache();
        app.initializeCache();
        rc.response().end("done");
    }

    @Blocking
    @Route(methods = Route.HttpMethod.GET)
    void discussionCategories(RoutingContext rc) throws IOException {
        String repo = "commonhaus/automation-test";
        RepoQuery repoQuery = app.getRepoQueryContext(repo);

        List<DiscussionCategory> categories = repoQuery.queryDiscussionCategories();
        rc.response().end("done. found " + categories.size() + " discussion categories");
    }

    @Blocking
    @Route(methods = Route.HttpMethod.GET)
    void discussionReaction(RoutingContext rc) throws IOException {
        String repo = "commonhaus/automation-test";
        RepoQuery repoQuery = app.getRepoQueryContext(repo);
        int count = 0;

        List<Discussion> discussions = repoQuery.queryDiscussions(true);
        for (Discussion d : discussions) {
            Log.infof("Discussion: %s : %s", d.id, d.title);
            Log.infof("Last Edit: %s by %s", d.lastEditedAt, d.editor);
            List<Reaction> reactions = Reaction.queryReactions(repoQuery, d.id);
            for (Reaction r : reactions) {
                Log.infof("Reaction: %s by %s at %s", r.content, r.user, r.createdAt);
            }
            count += reactions.size();
        }
        rc.response().end("done. found " + discussions.size() + " discussions and " + count + " reactions");
    }

    @Blocking
    @Route(methods = Route.HttpMethod.GET)
    void discussionLabel(RoutingContext rc) throws IOException {
        String repo = "commonhaus/automation-test";
        RepoQuery repoQuery = app.getRepoQueryContext(repo);
        int count = 0;

        List<Discussion> discussions = repoQuery.queryDiscussions(true);
        for (Discussion d : discussions) {
            Log.infof("Discussion: %s : %s", d.id, d.title);
            Log.infof("Last Edit: %s by %s", d.lastEditedAt, d.editor);
            List<Label> labels = Label.queryLabels(repoQuery, d.id);
            for (Label l : labels) {
                Log.infof("Label: %s", l);
            }
            count += labels.size();
        }
        rc.response().end("done. found " + discussions.size() + " discussions and " + count + " labels");
    }
}
