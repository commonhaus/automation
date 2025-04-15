package org.commonhaus.automation.hr;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.commonhaus.automation.config.LocalRouteOnly;
import org.commonhaus.automation.hr.voting.VoteProcessor;
import org.commonhaus.automation.queue.PeriodicUpdateQueue;

import io.quarkus.logging.Log;
import io.quarkus.vertx.web.Route;
import io.quarkus.vertx.web.Route.HttpMethod;
import io.quarkus.vertx.web.RoutingExchange;
import io.vertx.ext.web.RoutingContext;

@Singleton
public class AdminRoutes implements LocalRouteOnly {

    @Inject
    VoteProcessor voteProcessor;

    @Inject
    PeriodicUpdateQueue updateQueue;

    @Route(path = "/votes", order = 99, produces = "text/html", methods = { HttpMethod.GET })
    public void triggerVoteCount(RoutingContext routingContext, RoutingExchange routingExchange) {
        if (!isDirectConnection(routingExchange)) {
            rejectNonLocalAccess(routingExchange);
            return;
        }
        updateQueue.queueReconciliation("triggerVoteCount", () -> {
            Log.info("🚀 🗳️ vote counting triggered");
            voteProcessor.discoverVotes();
        });
        routingExchange.ok();
    }
}
