package org.commonhaus.automation.hk.dev;

import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.qute.Engine;
import io.quarkus.vertx.web.Route;
import io.quarkus.vertx.web.Route.HttpMethod;
import io.quarkus.vertx.web.RoutingExchange;
import io.vertx.ext.web.RoutingContext;

@UnlessBuildProfile("prod")
public class TestRoutes {
    final String memberHome;

    TestRoutes(Engine engine) {
        memberHome = engine.getTemplate("keeper.html").render();
    }

    @Route(path = "manager/org", order = 99, produces = "text/html", methods = { HttpMethod.GET })
    public void handleOrg(RoutingContext routingContext, RoutingExchange routingExchange) {
        routingExchange
                .ok()
                .putHeader("X-Robots-Tag", "noindex, nofollow, noarchive, nosnippet, notranslate, noimageindex")
                .end(memberHome);
    }

    @Route(path = "manager/projects", order = 99, produces = "text/html", methods = { HttpMethod.GET })
    public void handleProjects(RoutingContext routingContext, RoutingExchange routingExchange) {
        routingExchange
                .ok()
                .putHeader("X-Robots-Tag", "noindex, nofollow, noarchive, nosnippet, notranslate, noimageindex")
                .end(memberHome);
    }

    @Route(path = "manager/sponsors", order = 99, produces = "text/html", methods = { HttpMethod.GET })
    public void handleSponsors(RoutingContext routingContext, RoutingExchange routingExchange) {
        routingExchange
                .ok()
                .putHeader("X-Robots-Tag", "noindex, nofollow, noarchive, nosnippet, notranslate, noimageindex")
                .end(memberHome);
    }

    @Route(path = "rules/votes", order = 99, produces = "text/html", methods = { HttpMethod.GET })
    public void handleVotes(RoutingContext routingContext, RoutingExchange routingExchange) {
        routingExchange
                .ok()
                .putHeader("X-Robots-Tag", "noindex, nofollow, noarchive, nosnippet, notranslate, noimageindex")
                .end(memberHome);
    }
}
