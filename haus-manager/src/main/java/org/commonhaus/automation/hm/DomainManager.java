package org.commonhaus.automation.hm;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.UriBuilder;

import org.commonhaus.automation.config.RouteSupplier;
import org.commonhaus.automation.hm.config.ManagerBotConfig;
import org.commonhaus.automation.hm.github.AppContextService;
import org.commonhaus.automation.hm.namecheap.DomainListResponse;
import org.commonhaus.automation.hm.namecheap.DomainRecord;
import org.commonhaus.automation.hm.namecheap.NamecheapClient;
import org.commonhaus.automation.hm.namecheap.NamecheapResponseParser;
import org.commonhaus.automation.hm.namecheap.PaginationInfo;
import org.commonhaus.automation.queue.PeriodicUpdateQueue;
import org.commonhaus.automation.queue.ScheduledService;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.kohsuke.github.GHRepository;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.logging.Log;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class DomainManager extends ScheduledService implements ClientRequestFilter {
    static final String ME = "📋-domains";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Inject
    AppContextService ctx;

    @Inject
    ManagerBotConfig mgrBotConfig;

    @Inject
    PeriodicUpdateQueue updateQueue;

    @Inject
    ObjectMapper objectMapper;

    private Optional<NamecheapClient> namecheapClient;

    void startup(@Observes StartupEvent startup) {
        RouteSupplier.registerSupplier("Domain list refreshed", () -> lastRun);
        Log.infof("[%s] current configuration: %s", ME, mgrBotConfig.namecheap().orElse(null));
    }

    /**
     * Periodically refresh/re-synchronize sponsor collaborators
     */
    // Quartz cron expression: s m h dom mon dow year(optional)
    @Scheduled(cron = "${automation.hausManager.cron.domain:27 25 13 ? * THU *}")
    public void scheduledRefresh() {
        if (!isNamecheapAvailable()) {
            return;
        }
        try {
            Log.infof("[%s] ⏰ Scheduled: refresh domains", ME);
            refreshDomains(false);
        } catch (Throwable t) {
            ctx.logAndSendEmail(ME, "⏰ Error running scheduled domain refresh", t);
        }
    }

    public void refreshDomains(boolean userTriggered) {
        if (!isNamecheapAvailable()) {
            return;
        }
        if (!userTriggered && !taskState.shouldRun(ME, Duration.ofHours(24))) {
            Log.infof("[%s]: skip domain refresh (last run: %s)", ME, lastRun);
            return;
        }
        recordRun();
        try {
            List<DomainRecord> allDomains = fetchAllDomains();
            Log.infof("[%s] Retrieved %d domains from Namecheap", ME, allDomains.size());
            if (LaunchMode.current() != LaunchMode.NORMAL) {
                Log.infof("[%s] Domain list: %s", ME, objectMapper.writeValueAsString(allDomains));
                return;
            }
            dispatchDomainList(allDomains);
        } catch (Exception e) {
            ctx.logAndSendEmail(ME, "⛓️ Error fetching domains from Namecheap", e);
        }
    }

    public void getDomainInfo(String domainName) {
        var client = namecheapClient();
        if (client.isEmpty()) {
            Log.warn("Namecheap client not available");
            return;
        }
        try {
            var result = client.get().getDomainInfo(domainName);
            Log.infof("[%s] Domain: %s %s", ME, domainName, result);
        } catch (Exception e) {
            ctx.logAndSendEmail(ME, "⛓️ Error fetching domains from Namecheap", e);
        }
    }

    private List<DomainRecord> fetchAllDomains() {
        var client = namecheapClient();
        if (client.isEmpty()) {
            Log.warn("Namecheap client not available");
            return List.of();
        }

        List<DomainRecord> allDomains = new ArrayList<>();
        int currentPage = 1;
        int pageSize = 100; // Use larger page size for efficiency

        do {
            String xmlResponse = client.get().getDomainList(currentPage, pageSize);
            DomainListResponse response = NamecheapResponseParser.parseDomainListResponse(xmlResponse);

            allDomains.addAll(response.domains());
            PaginationInfo pagination = response.pagination();

            Log.debugf("[%s] Page %d: retrieved %d domains, total so far: %d",
                    ME, currentPage, response.domains().size(), allDomains.size());

            if (!pagination.hasMorePages()) {
                break;
            }

            currentPage = pagination.nextPage();
        } while (true);

        return allDomains;
    }

    Optional<NamecheapClient> namecheapClient() {
        if (namecheapClient == null && isNamecheapAvailable()) {
            var config = mgrBotConfig.namecheap().get();
            try {
                NamecheapClient client = RestClientBuilder.newBuilder()
                        .baseUri(java.net.URI.create(config.url()))
                        .followRedirects(true)
                        .register(this)
                        .build(NamecheapClient.class);

                Log.info("Namecheap REST client configured successfully");
                namecheapClient = Optional.of(client);
            } catch (Exception e) {
                Log.warn("Failed to create Namecheap REST client", e);
                namecheapClient = Optional.empty();
            }
        }
        return namecheapClient;
    }

    public boolean isNamecheapAvailable() {
        return mgrBotConfig.namecheap().isPresent();
    }

    private void dispatchDomainList(List<DomainRecord> domains) {
        var qc = ctx.getHomeQueryContext();
        var config = mgrBotConfig.namecheap().get();

        try {
            GHRepository repo = qc.getRepository(config.workflowRepository());

            Map<String, Object> payload = Map.of(
                    "date", LocalDate.now().format(DATE_FORMAT),
                    "domains", objectMapper.writeValueAsString(domains),
                    "count", domains.size());

            repo.dispatch(config.workflowName(), payload);

            Log.infof("[%s] Dispatched %d domains to workflow %s in %s",
                    ME, domains.size(), config.workflowName(), config.workflowRepository());
        } catch (Exception e) {
            qc.addException(e);
            qc.logAndSendContextErrors("Error sending domain list to " + config.workflowRepository());
        }
    }

    @Override
    protected String me() {
        return ME;
    }

    @Override
    public void filter(ClientRequestContext requestContext) {
        var config = mgrBotConfig.namecheap().get();

        // Add common query parameters
        UriBuilder uriBuilder = UriBuilder.fromUri(requestContext.getUri());
        uriBuilder.queryParam("ApiUser", config.username());
        uriBuilder.queryParam("ApiKey", config.apiKey());
        uriBuilder.queryParam("UserName", config.username());
        uriBuilder.queryParam("ClientIp", config.ipv4Addr());

        requestContext.setUri(uriBuilder.build());
    }
}
