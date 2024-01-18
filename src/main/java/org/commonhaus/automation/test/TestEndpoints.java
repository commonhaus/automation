package org.commonhaus.automation.test;

import java.io.IOException;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.commonhaus.automation.github.CFGHApp;
import org.commonhaus.automation.github.CFGHQueryContext;
import org.commonhaus.automation.github.CFGHRepoInfo;
import org.commonhaus.automation.github.Discussion;
import org.commonhaus.automation.github.Label;
import org.commonhaus.automation.github.Reaction;

import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.logging.Log;
import io.quarkus.vertx.web.Route;
import io.smallrye.common.annotation.Blocking;
import io.vertx.ext.web.RoutingContext;

@ApplicationScoped
@UnlessBuildProfile("prod")
public class TestEndpoints {

    @Inject
    CFGHApp installationManager;

    @Blocking
    @Route(methods = Route.HttpMethod.GET)
    void setup(RoutingContext rc) throws IOException {
        installationManager.purgeCache();
        installationManager.initializeCache();
        rc.response().end("done");
    }

    @Blocking
    @Route(methods = Route.HttpMethod.GET)
    void discussionReaction(RoutingContext rc) throws IOException {
        String repo = "commonhaus/automation-test";
        CFGHRepoInfo repositoryInfo = installationManager.getRepositoryInfo(repo);
        CFGHQueryContext queryContext = installationManager.getQueryContext(repositoryInfo);

        List<Discussion> discussions = repositoryInfo.queryDiscussions(queryContext, true);
        for (Discussion d : discussions) {
            Log.infof("Discussion: %s : %s", d.id, d.title);
            Log.infof("Last Edit: %s by %s", d.lastEditedAt, d.editor);
            List<Reaction> reactions = Reaction.queryReactions(queryContext, d.id);
            for (Reaction r : reactions) {
                Log.infof("Reaction: %s by %s at %s", r.content, r.user, r.createdAt);
            }
        }
        rc.response().end("done");
    }

    @Blocking
    @Route(methods = Route.HttpMethod.GET)
    void discussionLabel(RoutingContext rc) throws IOException {
        String repo = "commonhaus/automation-test";
        CFGHRepoInfo repositoryInfo = installationManager.getRepositoryInfo(repo);
        CFGHQueryContext queryContext = installationManager.getQueryContext(repositoryInfo);

        List<Discussion> discussions = repositoryInfo.queryDiscussions(queryContext, true);
        for (Discussion d : discussions) {
            Log.infof("Discussion: %s : %s", d.id, d.title);
            Log.infof("Last Edit: %s by %s", d.lastEditedAt, d.editor);
            List<Label> labels = Label.queryLabels(queryContext, d.id);
            for (Label l : labels) {
                Log.infof("Label: %s", l);
            }
        }
        rc.response().end("done");
    }
}
