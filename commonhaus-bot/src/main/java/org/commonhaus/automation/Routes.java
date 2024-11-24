package org.commonhaus.automation;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.quarkus.qute.Engine;
import io.quarkus.vertx.web.Route;
import io.quarkus.vertx.web.Route.HttpMethod;
import io.quarkus.vertx.web.RoutingExchange;
import io.vertx.ext.web.RoutingContext;

public class Routes {

    private static final Map<String, Supplier<String>> suppliers = new HashMap<>();

    public static void registerSupplier(String key, Supplier<String> supplier) {
        suppliers.put(key, supplier);
    }

    private final Engine engine;

    Routes(Engine engine) {
        this.engine = engine;
    }

    @Route(path = "/", order = 99, produces = "text/html", methods = { HttpMethod.GET })
    public void handleRootRequest(RoutingContext routingContext, RoutingExchange routingExchange) {
        Map<String, String> result = suppliers.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));

        routingExchange
                .ok()
                .putHeader("X-Robots-Tag", "noindex, nofollow, noarchive, nosnippet, notranslate, noimageindex")
                .end(engine.getTemplate("index.html").data("items", result).render());
    }

    @Route(path = "/ping", order = 99, produces = "text/html", methods = { HttpMethod.GET })
    public void handleRequest(RoutingContext routingContext, RoutingExchange routingExchange) {
        handleRequest(routingContext, routingExchange);
    }
}
