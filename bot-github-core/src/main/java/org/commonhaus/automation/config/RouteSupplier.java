package org.commonhaus.automation.config;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import jakarta.inject.Singleton;

import io.quarkus.qute.Engine;
import io.quarkus.vertx.web.Route;
import io.quarkus.vertx.web.Route.HttpMethod;
import io.quarkus.vertx.web.RoutingExchange;
import io.vertx.ext.web.RoutingContext;

@Singleton
public class RouteSupplier {
    private static final Map<String, Supplier<String>> suppliers = new HashMap<>();

    public static void registerSupplier(String key, Supplier<String> supplier) {
        suppliers.put(key, supplier);
    }

    public static Map<String, String> attributes() {
        return suppliers.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }

    private final Engine engine;

    RouteSupplier(Engine engine) {
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
}
