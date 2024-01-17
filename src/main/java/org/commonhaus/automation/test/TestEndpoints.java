package org.commonhaus.automation.test;

import java.io.IOException;
import java.util.List;

import org.commonhaus.automation.github.Discussion;
import org.commonhaus.automation.github.CFGHApp;
import org.commonhaus.automation.github.QueryContext;
import org.commonhaus.automation.github.Reaction;
import org.commonhaus.automation.github.RepositoryInfo;
import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.logging.Log;
import io.quarkus.vertx.web.Route;
import io.smallrye.common.annotation.Blocking;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
    void reaction(RoutingContext rc) throws IOException { 
        String repo = "commonhaus/automation-test";
        RepositoryInfo repositoryInfo = installationManager.getRepositoryInfo(repo);
        QueryContext queryContext = installationManager.getQueryContext(repositoryInfo);

        List<Discussion> discussions = repositoryInfo.listDiscussions(queryContext, true);
        for (Discussion d : discussions) {
            Log.infof("Discussion: %s : %s", d.id, d.title);
            Log.infof("Last Edit: %s by %s", d.lastEditedAt, d.editor.login);
            List<Reaction> reactions = Reaction.listReactions(queryContext, d.id);
            for (Reaction r : reactions) {
                Log.infof("Reaction: %s by %s at %s", r.content, r.user.login, r.createdAt);
            }
        }
        rc.response().end("done");
    }
}
