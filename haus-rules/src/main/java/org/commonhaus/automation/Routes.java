package org.commonhaus.automation;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

import org.commonhaus.automation.config.RouteSupplier;

import io.quarkus.qute.Engine;
import io.quarkus.vertx.web.Route;
import io.quarkus.vertx.web.Route.HttpMethod;
import io.quarkus.vertx.web.RoutingExchange;
import io.vertx.ext.web.RoutingContext;

public class Routes {
    private final Engine engine;

    Routes(Engine engine) {
        this.engine = engine;
    }

    @Route(path = "/", order = 99, produces = "text/html", methods = { HttpMethod.GET })
    public void handleRootRequest(RoutingContext routingContext, RoutingExchange routingExchange) {
        routingExchange
                .ok()
                .putHeader("X-Robots-Tag", "noindex, nofollow, noarchive, nosnippet, notranslate, noimageindex")
                .end(engine.getTemplate("index.html")
                        .data("items", RouteSupplier.attributes())
                        .data("now", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                        .render());
    }

    @Route(path = "/ping", order = 99, produces = "text/html", methods = { HttpMethod.GET })
    public void handleRequest(RoutingContext routingContext, RoutingExchange routingExchange) {
        handleRequest(routingContext, routingExchange);
    }
}
