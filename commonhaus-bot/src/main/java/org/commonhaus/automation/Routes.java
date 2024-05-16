package org.commonhaus.automation;

import io.quarkus.qute.Engine;
import io.quarkus.vertx.web.Route;
import io.quarkus.vertx.web.Route.HttpMethod;
import io.quarkus.vertx.web.RoutingExchange;
import io.vertx.ext.web.RoutingContext;

public class Routes {

    final String hello;

    Routes(Engine engine) {
        hello = engine.getTemplate("index.html").render();
    }

    @Route(path = "/", order = 99, produces = "text/html", methods = { HttpMethod.GET })
    public void handleRootRequest(RoutingContext routingContext, RoutingExchange routingExchange) {
        routingExchange
                .ok()
                .putHeader("X-Robots-Tag", "noindex, nofollow, noarchive, nosnippet, notranslate, noimageindex")
                .end(hello);
    }

    @Route(path = "/ping", order = 99, produces = "text/html", methods = { HttpMethod.GET })
    public void handleRequest(RoutingContext routingContext, RoutingExchange routingExchange) {
        routingExchange
                .ok()
                .putHeader("X-Robots-Tag", "noindex, nofollow, noarchive, nosnippet, notranslate, noimageindex")
                .end(hello);
    }
}
