package org.commonhaus.automation.hm;

import java.time.LocalDate;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.commonhaus.automation.config.LocalRouteOnly;
import org.commonhaus.automation.github.stats.ProjectHealthCollector;
import org.commonhaus.automation.hm.github.AppContextService;
import org.commonhaus.automation.mail.LogMailer;
import org.commonhaus.automation.queue.PeriodicUpdateQueue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.logging.Log;
import io.quarkus.vertx.web.Route;
import io.quarkus.vertx.web.Route.HttpMethod;
import io.quarkus.vertx.web.RoutingExchange;
import io.vertx.ext.web.RoutingContext;

@Singleton
public class AdminRoutes implements LocalRouteOnly {
    @Inject
    AppContextService ctx;

    @Inject
    OrganizationManager organizationManager;

    @Inject
    ProjectManager projectManager;

    @Inject
    SponsorManager sponsorManager;

    @Inject
    PeriodicUpdateQueue updateQueue;

    @Inject
    ProjectHealthCollector projectHealthCollector;

    @Inject
    LogMailer logMailer;

    @Inject
    ObjectMapper objectMapper;

    @Route(path = "/health", order = 99, produces = "text/html", methods = { HttpMethod.GET })
    public void triggerHealthUpdate(RoutingContext routingContext, RoutingExchange routingExchange) {
        if (!isDirectConnection(routingExchange)) {
            rejectNonLocalAccess(routingExchange);
            return;
        }

        routingExchange.ok().end();
    }

    @Route(path = "/org", order = 99, produces = "text/html", methods = { HttpMethod.GET })
    public void triggerOrgUpdate(RoutingContext routingContext, RoutingExchange routingExchange) {
        if (!isDirectConnection(routingExchange)) {
            rejectNonLocalAccess(routingExchange);
            return;
        }
        updateQueue.queueReconciliation("triggerOrgUpdate", () -> {
            Log.info("🚀 🏡 Organization update triggered");
            organizationManager.refreshOrganizationMembership(true);
        });
        routingExchange.ok().end();
    }

    @Route(path = "/projects", order = 99, produces = "text/html", methods = { HttpMethod.GET })
    public void triggerProjectUpdate(RoutingContext routingContext, RoutingExchange routingExchange) {
        if (!isDirectConnection(routingExchange)) {
            rejectNonLocalAccess(routingExchange);
            return;
        }
        updateQueue.queueReconciliation("triggerProjectUpdate", () -> {
            Log.info("🚀 🌳 Project update triggered");
            projectManager.refreshConfig(true);
        });
        routingExchange.ok().end();
    }

    @Route(path = "/sponsors", order = 99, produces = "text/html", methods = { HttpMethod.GET })
    public void triggerSponsorUpdate(RoutingContext routingContext, RoutingExchange routingExchange) {
        if (!isDirectConnection(routingExchange)) {
            rejectNonLocalAccess(routingExchange);
            return;
        }
        updateQueue.queueReconciliation("triggerSponsorUpdate", () -> {
            Log.info("🚀 💸 Sponsors update triggered");
            sponsorManager.refreshSponsors(true);
        });
        routingExchange.ok().end();
    }

    @Route(path = "/statistics", order = 99, produces = "text/html", methods = { HttpMethod.GET })
    public void triggerStatistics(RoutingContext routingContext, RoutingExchange routingExchange) {
        if (!isDirectConnection(routingExchange)) {
            rejectNonLocalAccess(routingExchange);
            return;
        }

        var request = routingContext.request();
        if (request.params().isEmpty()) {
            Log.info("Statistics request without parameters (fullName, startDate)");
            routingExchange.response().setStatusCode(400).end();
            return;
        }
        var fullName = routingContext.request().getParam("fullName");
        if (fullName == null || fullName.isBlank()) {
            Log.info("Statistics request without fullName");
            routingExchange.response().setStatusCode(400).end();
            return;
        }
        var qc = ctx.getOrgScopedQueryContext(fullName);
        if (qc == null) {
            Log.infof("Unable to create query context for %s", fullName);
            routingExchange.notFound().end();
            return;
        }

        var dateString = routingContext.request().getParam("startDate");
        var startDate = parseDate(dateString);

        updateQueue.queueReconciliation("triggerStatistics/" + fullName, () -> {
            Log.infof("🚀 📊 Statistics update triggered for %s", fullName);
            var report = projectHealthCollector.collect(qc, startDate, true, true);
            try {
                var reportAsString = objectMapper.writeValueAsString(report);
                logMailer.sendEmail(
                        qc.getLogId(),
                        "Statistics update for " + fullName,
                        reportAsString,
                        ctx.botErrorEmailAddress());
            } catch (JsonProcessingException e) {
                logMailer.logAndSendEmail(qc.getLogId(),
                        "Error serializing statistics update for " + fullName,
                        e, ctx.botErrorEmailAddress());
            }

        });
        routingExchange.ok().end();
    }

    private LocalDate parseDate(String dateString) {
        if (dateString == null || dateString.isBlank()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(dateString);
        } catch (Exception e) {
            Log.error("Invalid date format: " + dateString, e);
            return LocalDate.now();
        }
    }
}
