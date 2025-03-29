package org.commonhaus.automation.config;

import io.quarkus.vertx.web.RoutingExchange;

public interface LocalRouteOnly {
    default boolean isDirectConnection(RoutingExchange rex) {
        var request = rex.context().request();
        // Check if X-Forwarded-For or other proxy headers are absent
        return request.getHeader("X-Forwarded-For") == null &&
                request.getHeader("X-Real-IP") == null;
    }

    /**
     * Handle an unauthorized access attempt
     */
    default void rejectNonLocalAccess(RoutingExchange rex) {
        rex.context().response()
                .setStatusCode(403)
                .end("Access denied");
    }
}
