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

import org.commonhaus.automation.config.RouteSupplier;
import org.commonhaus.automation.hm.ProjectManager.ProjectConfigState;
import org.commonhaus.automation.hm.config.DomainManagementConfig;
import org.commonhaus.automation.hm.config.LatestProjectConfig;
import org.commonhaus.automation.hm.config.LatestProjectConfig.ProjectConfigListener;
import org.commonhaus.automation.hm.config.ManagedDomain;
import org.commonhaus.automation.hm.config.ManagerBotConfig;
import org.commonhaus.automation.hm.github.AppContextService;
import org.commonhaus.automation.hm.namecheap.NamecheapService;
import org.commonhaus.automation.hm.namecheap.models.ContactInfo;
import org.commonhaus.automation.hm.namecheap.models.DomainContacts;
import org.commonhaus.automation.hm.namecheap.models.DomainInformation;
import org.commonhaus.automation.hm.namecheap.models.DomainRecord;
import org.commonhaus.automation.queue.PeriodicUpdateQueue;
import org.commonhaus.automation.queue.ScheduledService;
import org.kohsuke.github.GHRepository;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.logging.Log;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class DomainManager extends ScheduledService implements ProjectConfigListener {
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

    @Inject
    NamecheapService namecheapService;

    @Inject
    LatestProjectConfig latestProjectConfig;

    void startup(@Observes StartupEvent startup) {
        RouteSupplier.registerSupplier("Domain list refreshed", () -> lastRun);
        // Register for project config updates
        latestProjectConfig.notifyOnUpdate(ME, this);
    }

    @Override
    public String getTaskGroup(String repoFullName) {
        return ME + "#" + repoFullName;
    }

    @Override
    public void onProjectConfigUpdate(String taskGroup, ProjectConfigState state) {
        Log.debugf("[%s] Project config updated for %s", ME, taskGroup);

        DomainManagementConfig domainConfig = state.projectConfig().domainManagement();
        if (domainConfig == null || !domainConfig.isEnabled()) {
            Log.debugf("[%s] %s: Domain management disabled, skipping", ME, taskGroup);
            return;
        }

        Log.infof("[%s] Domain management enabled for %s: %s", ME, state.repoFullName(), domainConfig);

        // Queue contact synchronization for this project's domains
        updateQueue.queueReconciliation(taskGroup,
                () -> syncContactsForProject(state, domainConfig));
    }

    /**
     * Periodically refresh/re-synchronize sponsor collaborators
     */
    // Quartz cron expression: s m h dom mon dow year(optional)
    @Scheduled(cron = "${automation.hausManager.cron.domain:27 25 13 ? * THU *}")
    public void scheduledRefresh() {
        try {
            Log.infof("[%s] ⏰ Scheduled: refresh domains", ME);
            refreshDomains(false);
        } catch (Throwable t) {
            ctx.logAndSendEmail(ME, "⏰ Error running scheduled domain refresh", t);
        }
    }

    public void refreshDomains(boolean userTriggered) {
        if (!userTriggered && !taskState.shouldRun(ME, Duration.ofHours(24))) {
            Log.infof("[%s]: skip domain refresh (last run: %s)", ME, lastRun);
            return;
        }
        recordRun();
        try {
            List<DomainRecord> allDomains = namecheapService.fetchAllDomains();
            Log.infof("[%s] Retrieved %d domains from Namecheap", ME, allDomains.size());

            // Enrich domains with contact information
            List<DomainInformation> domainWithContacts = fetchDomainContacts(allDomains);
            Log.infof("[%s] Updated %d domains with contact information", ME, domainWithContacts.size());

            if (LaunchMode.current() != LaunchMode.NORMAL) {
                Log.infof("[%s] Domain list: %s", ME, objectMapper.writeValueAsString(domainWithContacts));
                return;
            }
            dispatchDomainList(domainWithContacts);
        } catch (Exception e) {
            ctx.logAndSendEmail(ME, "⛓️ Error fetching domains from Namecheap", e);
        }
    }

    /**
     * Enrich domain records with contact information from Namecheap.
     * Only includes domains where contact fetch succeeds.
     * Note: NamecheapService handles error logging and email notifications for API failures.
     */
    private List<DomainInformation> fetchDomainContacts(List<DomainRecord> domains) {
        List<DomainInformation> enriched = new ArrayList<>();

        for (DomainRecord domain : domains) {
            Optional<DomainContacts> contacts = namecheapService.getContacts(domain.name());
            if (contacts.isPresent()) {
                enriched.add(new DomainInformation(domain, contacts.get()));
            } else {
                Log.warnf("[%s] Failed to fetch contacts for %s, excluding from report", ME, domain.name());
            }
        }

        return enriched;
    }

    private void dispatchDomainList(List<DomainInformation> domains) {
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

    /**
     * Synchronize contacts for domains registered in a project configuration
     */
    private void syncContactsForProject(ProjectConfigState state, DomainManagementConfig domainConfig) {
        Log.infof("[%s] Syncing contacts for project: %s", ME, state.repoFullName());

        if (domainConfig.domains().isEmpty()) {
            Log.debugf("[%s] No domains configured for %s", ME, state.repoFullName());
            return;
        }

        // Get default contacts from NamecheapService (from bot config)
        DomainContacts defaultContacts = namecheapService.defaultContacts();

        for (ManagedDomain managedDomain : domainConfig.domains()) {
            try {
                syncDomainContacts(managedDomain, domainConfig, defaultContacts, domainConfig.isDryRun());
            } catch (Exception e) {
                ctx.logAndSendEmail(ME,
                        "Error syncing contacts for domain: " + managedDomain.name(), e);
            }
        }
    }

    /**
     * Synchronize contacts for a single domain
     */
    private void syncDomainContacts(ManagedDomain managedDomain, DomainManagementConfig domainConfig,
            DomainContacts defaultContacts, boolean dryRun) {

        String domainName = managedDomain.name();
        Log.debugf("[%s] Checking contacts for domain: %s (dryRun=%s)", ME, domainName, dryRun);

        // Fetch current contacts from Namecheap
        Optional<DomainContacts> currentContacts = namecheapService.getContacts(domainName);
        if (currentContacts.isEmpty()) {
            Log.warnf("[%s] Failed to fetch current contacts for %s", ME, domainName);
            return;
        }

        // Build desired contacts by merging: bot defaults + project tech contact + domain-specific tech contact
        DomainContacts desiredContacts = buildDesiredContacts(
                managedDomain, domainConfig, defaultContacts);

        // Check if update is needed
        if (!desiredContacts.requiresUpdate(currentContacts.get())) {
            Log.debugf("[%s] Contacts for %s are up to date", ME, domainName);
            return;
        }

        Log.infof("[%s] Contacts for %s require update", ME, domainName);

        if (dryRun) {
            Log.infof("[%s] DRY RUN: Would update contacts for %s", ME, domainName);
            return;
        }

        // Update contacts
        boolean success = namecheapService.setContacts(domainName, desiredContacts);
        if (success) {
            Log.infof("[%s] Successfully updated contacts for %s", ME, domainName);
        } else {
            Log.errorf("[%s] Failed to update contacts for %s", ME, domainName);
        }
    }

    /**
     * Build desired contacts by using bot defaults and validating project/domain tech contact overrides.
     * Tech contact hierarchy: domain-specific > project-level > bot default
     * If a tech contact override is specified, it must be valid (all required fields present).
     * We use it as-is without merging if valid; otherwise fall back to default.
     */
    private DomainContacts buildDesiredContacts(ManagedDomain managedDomain,
            DomainManagementConfig domainConfig, DomainContacts defaultContacts) {

        ContactInfo registrant = defaultContacts.registrant();
        ContactInfo admin = defaultContacts.admin();
        ContactInfo billing = defaultContacts.auxBilling();

        // Start with tech contact from bot defaults
        ContactInfo tech = defaultContacts.tech();

        // Check for domain-specific tech contact override (highest priority)
        if (managedDomain.techContact().map(c -> c.isValid(ctx, ME, managedDomain.name())).orElse(false)) {
            tech = ContactInfo.fromConfig(managedDomain.techContact().get());
            Log.debugf("[%s] Using domain-specific tech contact for %s", ME, managedDomain.name());
        }
        // Check for project-level tech contact override (medium priority)
        else if (domainConfig.getTechContact().map(c -> c.isValid(ctx, ME, managedDomain.name())).orElse(false)) {
            tech = ContactInfo.fromConfig(domainConfig.getTechContact().get());
            Log.debugf("[%s] Using project-level tech contact for %s", ME, managedDomain.name());
        }

        return new DomainContacts(registrant, tech, admin, billing);
    }

    @Override
    protected String me() {
        return ME;
    }
}
