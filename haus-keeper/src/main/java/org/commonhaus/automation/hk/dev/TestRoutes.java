package org.commonhaus.automation.hk.dev;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.qute.Engine;
import io.quarkus.vertx.web.Route;
import io.quarkus.vertx.web.Route.HttpMethod;
import io.quarkus.vertx.web.RoutingExchange;
import io.vertx.ext.web.RoutingContext;

@IfBuildProfile("dev")
public class TestRoutes {
    final String memberHome;

    TestRoutes(Engine engine) {
        memberHome = engine.getTemplate("member.html").render();
    }

    @Route(path = "/test", order = 99, produces = "text/html", methods = { HttpMethod.GET })
    public void handleMemberRequest(RoutingContext routingContext, RoutingExchange routingExchange) {
        routingExchange
                .ok()
                .putHeader("X-Robots-Tag", "noindex, nofollow, noarchive, nosnippet, notranslate, noimageindex")
                .end(memberHome);
    }
}
