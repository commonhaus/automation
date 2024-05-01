package org.commonhaus.automation;

import jakarta.inject.Inject;

import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;
import io.quarkus.vertx.web.Route;
import io.quarkus.vertx.web.Route.HttpMethod;
import io.quarkus.vertx.web.RoutingExchange;
import io.vertx.ext.web.RoutingContext;

public class Routes {

    @Inject
    Engine engine;

    @Route(path = "/", order = 99, produces = "text/html", methods = { HttpMethod.GET })
    public void handleRootRequest(RoutingContext routingContext, RoutingExchange routingExchange) {
        Template tpl = engine.getTemplate("index.html");
        routingExchange
                .ok()
                .putHeader("X-Robots-Tag", "noindex, nofollow, noarchive, nosnippet, notranslate, noimageindex")
                .end(tpl.render());
    }

    @Route(path = "/ping", order = 99, produces = "text/html", methods = { HttpMethod.GET })
    public void handleRequest(RoutingContext routingContext, RoutingExchange routingExchange) {
        Template tpl = engine.getTemplate("index.html");
        routingExchange
                .ok()
                .putHeader("X-Robots-Tag", "noindex, nofollow, noarchive, nosnippet, notranslate, noimageindex")
                .end(tpl.render());
    }
}
