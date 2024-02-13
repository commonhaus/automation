package org.commonhaus.automation;

import java.io.IOException;

import jakarta.inject.Inject;

import io.quarkus.logging.Log;
import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;
import io.quarkus.vertx.web.Route;
import io.quarkus.vertx.web.RoutingExchange;
import io.vertx.ext.web.RoutingContext;

public class Routes {

    @Inject
    Engine engine;

    @Route(path = "/ping", order = 99, produces = "text/html")
    public void handleRequest(RoutingContext routingContext, RoutingExchange routingExchange) throws IOException {
        Template tpl = engine.getTemplate("index.html");
        Log.debug("handle plain request");
        routingExchange
                .ok()
                .putHeader("X-Robots-Tag", "noindex, nofollow, noarchive, nosnippet, notranslate, noimageindex")
                .end(tpl.render());
    }
}
