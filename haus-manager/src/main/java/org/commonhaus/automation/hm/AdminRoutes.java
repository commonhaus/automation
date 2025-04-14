package org.commonhaus.automation.hm;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.commonhaus.automation.config.LocalRouteOnly;

import io.quarkus.logging.Log;
import io.quarkus.vertx.web.Route;
import io.quarkus.vertx.web.Route.HttpMethod;
import io.quarkus.vertx.web.RoutingExchange;
import io.vertx.ext.web.RoutingContext;

@Singleton
public class AdminRoutes implements LocalRouteOnly {

    @Inject
    OrganizationManager organizationManager;

    @Inject
    ProjectAccessManager projectManager;

    @Inject
    SponsorManager sponsorManager;

    @Route(path = "/org", order = 99, produces = "text/html", methods = { HttpMethod.GET })
    public void triggerOrgUpdate(RoutingContext routingContext, RoutingExchange routingExchange) {
        if (!isDirectConnection(routingExchange)) {
            rejectNonLocalAccess(routingExchange);
            return;
        }

        Log.info("🚀 🏡 Organization update triggered");
        organizationManager.refreshOrganizationMembership(true);
        routingExchange.ok();
    }

    @Route(path = "/projects", order = 99, produces = "text/html", methods = { HttpMethod.GET })
    public void triggerProjectUpdate(RoutingContext routingContext, RoutingExchange routingExchange) {
        if (!isDirectConnection(routingExchange)) {
            rejectNonLocalAccess(routingExchange);
            return;
        }

        Log.info("🚀 🌳 Project update triggered");
        projectManager.refreshAccessLists(true);
        routingExchange.ok();
    }

    @Route(path = "/sponsors", order = 99, produces = "text/html", methods = { HttpMethod.GET })
    public void triggerSponsorUpdate(RoutingContext routingContext, RoutingExchange routingExchange) {
        if (!isDirectConnection(routingExchange)) {
            rejectNonLocalAccess(routingExchange);
            return;
        }

        Log.info("🚀 💸 Sponsors update triggered");
        sponsorManager.refreshSponsors(true);
        routingExchange.ok();
    }
}
