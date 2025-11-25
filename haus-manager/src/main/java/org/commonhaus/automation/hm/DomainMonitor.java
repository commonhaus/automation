package org.commonhaus.automation.hm;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.config.EmailNotification;
import org.commonhaus.automation.config.RouteSupplier;
import org.commonhaus.automation.hm.ProjectManager.ProjectConfigState;
import org.commonhaus.automation.hm.config.DomainManagementConfig;
import org.commonhaus.automation.hm.config.LatestOrgConfig;
import org.commonhaus.automation.hm.config.LatestProjectConfig;
import org.commonhaus.automation.hm.config.ManagedDomain;
import org.commonhaus.automation.hm.config.ManagerBotConfig;
import org.commonhaus.automation.hm.github.AppContextService;
import org.commonhaus.automation.hm.github.ReportQueryContext;
import org.commonhaus.automation.hm.namecheap.NamecheapService;
import org.commonhaus.automation.hm.namecheap.models.ContactInfo;
import org.commonhaus.automation.hm.namecheap.models.DomainContacts;
import org.commonhaus.automation.hm.namecheap.models.DomainRecord;
import org.commonhaus.automation.queue.PeriodicUpdateQueue;
import org.commonhaus.automation.queue.ScheduledService;
import org.kohsuke.github.GHRepository;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class DomainMonitor extends ScheduledService {
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
    LatestOrgConfig latestOrgConfig;

    @Inject
    LatestProjectConfig latestProjectConfig;

    void startup(@Observes StartupEvent startup) {
        RouteSupplier.registerSupplier("Domain list refreshed", () -> lastRun);
    }

    /**
     * Periodically refresh/re-synchronize domain information
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

    /**
     * Update information we know about registered domains
     *
     * @param userTriggered true if triggered manually
     */
    public void refreshDomains(boolean userTriggered) {
        recordRun();
        if (!namecheapService.isEnabled() || !latestOrgConfig.getConfig().isDomainMonitoringEnabled()) {
            Log.infof("[%s]: domain monitoring is disabled (last run: %s)", ME, lastRun);
            return;
        }
        if (!userTriggered && !taskState.shouldRun(ME, Duration.ofHours(24))) {
            Log.infof("[%s]: skip domain refresh (last run: %s)", ME, lastRun);
            return;
        }

        // Get dry-run setting
        var dryRun = latestOrgConfig.getConfig().isMonitoringDryRun();

        if (dryRun) {
            Log.infof("[%s]: MONITORING IN DRY RUN MODE - no emails or updates", ME);
        }

        try {
            // 1. What NameCheap knows
            List<DomainRecord> namecheapDomains = namecheapService.fetchAllDomains();
            Log.infof("[%s] Retrieved %d domain(s) from Namecheap", ME, namecheapDomains.size());

            // 2. What the organization knows
            var orgDomainProject = latestOrgConfig.getConfig().projects().expectedDomains();
            var orgDomainMgmt = latestOrgConfig.getConfig().domainManagement();

            // 3. What the projects know
            var projectDomainsMgmt = latestProjectConfig.getAllProjects().stream()
                    .filter(p -> p.projectConfig() != null
                            && p.projectConfig().domainManagement() != null
                            && p.projectConfig().domainManagement().isEnabled())
                    .collect(Collectors.toMap(
                            p -> p.repoFullName(),
                            p -> p.projectConfig().domainManagement()));

            // 4. Reconcile sources
            var domainReconciliation = reconcileDomainSources(
                    namecheapDomains,
                    orgDomainMgmt.isEnabled() ? orgDomainMgmt.domains() : List.of(),
                    orgDomainProject,
                    projectDomainsMgmt);

            // 5. Process reconciliation results
            var validDomains = processDomainReconciliation(domainReconciliation);

            // 6. Synchronize contacts for valid domains
            for (var validDomain : validDomains) {
                if (validDomain.orgManagedDirectly) {
                    syncContactsForProject(
                            mgrBotConfig.home().repositoryFullName(),
                            orgDomainMgmt,
                            latestOrgConfig.getConfig().emailNotifications());
                } else {
                    // valid domains will have a set with exactly one project
                    var project = validDomain.projectsClaimingDomain().iterator().next();
                    syncContactsForProject(
                            project,
                            projectDomainsMgmt.get(project),
                            latestProjectConfig.getProjectConfigState(project).emailNotifications());
                }
            }

            // 7. Dispatch full domain list to GitHub Actions workflow for reporting
            if (!dryRun) {
                dispatchDomainList(namecheapDomains);
            }
        } catch (Exception e) {
            ctx.logAndSendEmail(ME, "⛓️ Error fetching domain(s) from Namecheap", e);
        }
    }

    private void dispatchDomainList(List<DomainRecord> namecheapDomains) {
        var config = mgrBotConfig.namecheap().get();
        if (!config.hasWorkflowConfig()) {
            Log.infof("[%s] No workflow configuration for domain list dispatch; skipping", ME);
            return;
        }

        ReportQueryContext rqc = ctx.getReportQueryContext(config.workflowRepository());

        try {
            GHRepository repo = rqc.getRepository();
            if (repo == null) {
                Log.errorf("[%s] Cannot dispatch domain list: repository %s not found",
                        ME, config.workflowRepository());
                return;
            }

            Map<String, Object> payload = Map.of(
                    "date", LocalDate.now().format(DATE_FORMAT),
                    "domains", objectMapper.writeValueAsString(namecheapDomains),
                    "count", namecheapDomains.size());

            repo.dispatch(config.workflowName(), payload);

            Log.infof("[%s] Dispatched %d domain(s) to workflow %s in %s",
                    ME, namecheapDomains.size(), config.workflowName(), config.workflowRepository());
        } catch (Exception e) {
            rqc.addException(e);
            rqc.logAndSendContextErrors("Error sending domain list to " + config.workflowRepository());
        }
    }

    /**
     * Synchronize contacts for domains registered in a project configuration
     *
     * @param emailNotification
     */
    private void syncContactsForProject(String repoFullName, DomainManagementConfig domainConfig,
            EmailNotification emailNotification) {
        Log.infof("[%s] Syncing contacts for project: %s", ME, repoFullName);

        if (domainConfig.domains().isEmpty()) {
            Log.debugf("[%s] No domains configured for %s", ME, repoFullName);
            return;
        }

        // Get default contacts from NamecheapService (from bot config)
        DomainContacts defaultContacts = namecheapService.defaultContacts();

        // Combine org-level monitoring dry-run with domain-specific dry-run
        boolean effectiveDryRun = latestOrgConfig.getConfig().isMonitoringDryRun() || domainConfig.isDryRun();

        for (ManagedDomain managedDomain : domainConfig.domains()) {
            try {
                syncDomainContacts(managedDomain, domainConfig, defaultContacts, effectiveDryRun, emailNotification);
            } catch (Exception e) {
                ctx.logAndSendEmail(ME,
                        "Error syncing contacts for domain: " + managedDomain.name(), e);
            }
        }
    }

    /**
     * Synchronize contacts for a single domain
     *
     * @param emailNotification
     */
    private void syncDomainContacts(ManagedDomain managedDomain, DomainManagementConfig domainConfig,
            DomainContacts defaultContacts, boolean dryRun, EmailNotification emailNotification) {

        String domainName = managedDomain.name();
        Log.debugf("[%s] Checking contacts for domain: %s (dryRun=%s)", ME, domainName, dryRun);

        // Fetch current contacts from Namecheap
        Optional<DomainContacts> currentContacts = Optional.empty();
        try {
            currentContacts = namecheapService.getContacts(domainName);
        } catch (Exception e) {
            Log.errorf(e, "[%s] Error fetching current contacts for %s", ME, domainName);
            return;
        }

        // Build desired contacts by merging: bot defaults + project tech contact +
        // domain-specific tech contact
        DomainContacts desiredContacts = buildDesiredContacts(
                managedDomain, domainConfig, defaultContacts);

        // Is update required?
        if (currentContacts.map(c -> !desiredContacts.requiresUpdate(c)).orElse(false)) {
            Log.debugf("[%s] Contacts for %s are up to date", ME, domainName);
            return;
        }

        Log.infof("[%s] Contacts for %s require update", ME, domainName);

        if (dryRun) {
            Log.infof("[%s] DRY RUN: Would update contacts for %s", ME, domainName);
            ctx.sendEmail(ME, "DRY RUN: Domain contact updates",
                    """
                            Contacts would be updated for domain %s.

                            Current contacts:
                            %s

                            Desired contacts:
                            %s

                            """.formatted(domainName, currentContacts.map(c -> c.prettyString()).orElse("none"),
                            desiredContacts.prettyString()),
                    emailNotification.dryRun());
            return;
        }

        try {
            if (namecheapService.setContacts(domainName, desiredContacts)) {
                Log.infof("[%s] Successfully updated contacts for %s", ME, domainName);
                ctx.sendEmail(ME, "Domain contacts updated",
                        """
                                Contacts updated for domain %s.

                                Previous contacts:
                                %s

                                Updated contacts:
                                %s

                                """.formatted(domainName, currentContacts.map(c -> c.prettyString()).orElse("none"),
                                desiredContacts.prettyString()),
                        emailNotification.audit());
            } else {
                ctx.logAndSendEmail(ME, "[%s] Failed to update contacts for %s".formatted(ME, domainName), null);
            }
        } catch (Exception e) {
            Log.errorf(e, "[%s] Error updating contacts for %s", ME, domainName);
            return;
        }
    }

    /**
     * Build desired contacts by using bot defaults and validating project/domain
     * tech contact overrides.
     * Tech contact hierarchy: domain-specific > project-level > bot default
     * If a tech contact override is specified, it must be valid (all required
     * fields present).
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

    /**
     * Process domain reconciliation results and take appropriate actions
     */
    private List<DomainReconciliation> processDomainReconciliation(Map<String, DomainReconciliation> reconciliation) {
        List<DomainReconciliation> orgProjectConflicts = new ArrayList<>();
        List<DomainReconciliation> multipleProjectConflicts = new ArrayList<>();
        List<DomainReconciliation> projectMismatches = new ArrayList<>();
        List<DomainReconciliation> orphanDomains = new ArrayList<>();
        List<DomainReconciliation> missingFromNamecheap = new ArrayList<>();
        List<DomainReconciliation> validDomains = new ArrayList<>();

        // Categorize each domain (order matters - check conflicts first)
        for (var recon : reconciliation.values()) {
            if (recon.hasOrgProjectConflict()) {
                // Org managing directly + project(s) claiming = conflict
                orgProjectConflicts.add(recon);
            } else if (recon.hasMultipleClaimants()) {
                // Multiple projects claiming same domain = conflict
                multipleProjectConflicts.add(recon);
            } else if (recon.hasOrgMismatch()) {
                // Project claiming domain not assigned to them in org config
                // or vice versa
                projectMismatches.add(recon);
            } else if (recon.isOrphan()) {
                // In NameCheap but not in any config (org or project)
                orphanDomains.add(recon);
            } else if (recon.isInOrgConfig() && !recon.isInNameCheap()) {
                // Expected in org config (org-managed or project-assigned) but not registered
                missingFromNamecheap.add(recon);
            } else if (recon.isInNameCheap() && recon.isInOrgConfig()) {
                // Valid: registered, managed by org directly or claimed by one project matching
                // org config
                validDomains.add(recon);
            } else {
                // Defensive: catch any unexpected domain states
                Log.errorf("[%s] Uncategorized domain %s: inNC=%s, orgManaged=%s, orgExpected=%s, projClaims=%s",
                        ME, recon.domainName(), recon.isInNameCheap(), recon.orgManagedDirectly,
                        recon.orgExpectedProjects(), recon.projectsClaimingDomain());
            }
        }

        // Log summary
        Log.infof("[%s] Domain reconciliation: %d valid, %d org/project conflicts, %d multi-project conflicts, " +
                "%d mismatches, %d orphan, %d missing",
                ME, validDomains.size(), orgProjectConflicts.size(), multipleProjectConflicts.size(),
                projectMismatches.size(), orphanDomains.size(), missingFromNamecheap.size());

        // Process each category
        if (!orgProjectConflicts.isEmpty()) {
            handleOrgProjectConflicts(orgProjectConflicts);
        }
        if (!multipleProjectConflicts.isEmpty()) {
            handleMultipleProjectConflicts(multipleProjectConflicts);
        }
        if (!projectMismatches.isEmpty()) {
            handleProjectMismatches(projectMismatches);
        }
        if (!orphanDomains.isEmpty()) {
            handleOrphanDomains(orphanDomains);
        }
        if (!missingFromNamecheap.isEmpty()) {
            handleMissingDomains(missingFromNamecheap);
        }
        if (!validDomains.isEmpty()) {
            handleValidDomains(validDomains);
        }

        return validDomains;
    }

    private void createProjectIssueAndMail(String title, String messageFormat,
            ProjectConfigState state, boolean isError) {

        var addresses = isError
                ? state.projectConfig().emailNotifications().errors()
                : state.projectConfig().emailNotifications().audit();
        var message = messageFormat.formatted(state.repoFullName());

        if (latestOrgConfig.getConfig().isMonitoringDryRun()) {
            Log.infof("[%s] DRY RUN: would create issue and send email for project %s to %s. title: %s; body: %s",
                    ME, state.repoFullName(), String.join(", ", addresses), title, message);
            return;
        }

        ctx.sendEmail(ME, title, message, addresses);

        // TODO: Create if absent
        // ReportQueryContext rqc = ctx.getReportQueryContext(state.repoFullName());
        // rqc.createItem(EventType.issue, title, message, null);
    }

    private void createOrgIssueAndMail(String title, String message, boolean isError) {
        var addresses = isError
                ? latestOrgConfig.getConfig().emailNotifications().errors()
                : latestOrgConfig.getConfig().emailNotifications().audit();

        if (latestOrgConfig.getConfig().isMonitoringDryRun()) {
            Log.infof("[%s] DRY RUN: would create issue and send email to %s. title: %s; body: %s",
                    ME, String.join(", ", addresses), title, message);
            return;
        }

        ctx.sendEmail(ME, title, message, addresses);

        // TODO: Create if absent
        // ReportQueryContext rqc =
        // ctx.getReportQueryContext(mgrBotConfig.home().repositoryFullName());
        // rqc.createItem(EventType.issue, title, message, null);

    }

    private void handleOrgProjectConflicts(List<DomainReconciliation> conflicts) {
        for (var conflict : conflicts) {
            Log.warnf("[%s]   %s: org-managed + claimed by %s", ME,
                    conflict.domainName(), String.join(", ", conflict.projectsClaimingDomain()));

            String title = "haus-manager: Domain conflict for " + conflict.domainName();

            // Notify org about the conflict
            String orgMessage = """
                    %s is managed by the organization but is also declared in project configuration(s): %s.

                    This conflict prevents domain contact updates. The project(s) have been notified to remove the domain from their configuration.
                    """
                    .formatted(
                            conflict.domainName(),
                            String.join(", ", conflict.projectsClaimingDomain()));

            createOrgIssueAndMail(title, orgMessage, true);

            // Notify projects claiming the domain
            String projectMessage = """
                    %s is both owned and managed by the parent foundation, it should not be present in your project configuration.

                    Please check the domainManagement section of your haus-manager configuration in %%s.
                    """
                    .formatted(
                            conflict.domainName());

            for (var project : conflict.projectsClaimingDomain()) {
                createProjectIssueAndMail(title, projectMessage,
                        latestProjectConfig.getProjectConfigState(project), true);
            }
        }
    }

    private void handleMultipleProjectConflicts(List<DomainReconciliation> conflicts) {
        for (var conflict : conflicts) {
            Log.warnf("[%s]   %s claimed by: %s", ME,
                    conflict.domainName(), String.join(", ", conflict.projectsClaimingDomain()));

            String title = "haus-manager: Domain conflict for " + conflict.domainName();
            String message = """
                    %s is declared in multiple project configurations: %s.

                    Please check the domainManagement section of your haus-manager configuration in %%s,
                    and remove the domain if it does not belong to your project.
                    """
                    .formatted(
                            conflict.domainName(),
                            String.join(", ", conflict.projectsClaimingDomain()));

            for (var project : conflict.projectsClaimingDomain()) {
                createProjectIssueAndMail(title, message,
                        latestProjectConfig.getProjectConfigState(project), true);
            }

            message = """
                    %s is declared in multiple project configurations: %s.

                    Projects have been notified to resolve the conflict.
                    """
                    .formatted(
                            conflict.domainName(),
                            String.join(", ", conflict.projectsClaimingDomain()));
            createOrgIssueAndMail(title, message, true);
        }
    }

    private void handleProjectMismatches(List<DomainReconciliation> mismatches) {
        for (var mismatch : mismatches) {
            Log.warnf("[%s]   %s: expected for %s, claimed by %s", ME,
                    mismatch.domainName(),
                    mismatch.orgExpectedProjects().isEmpty() ? "none"
                            : String.join(", ", mismatch.orgExpectedProjects()),
                    String.join(", ", mismatch.projectsClaimingDomain()));

            String title = "haus-manager: Domain assignment mismatch for " + mismatch.domainName();

            // Email projects that are claiming but shouldn't be
            Set<String> claimingButNotAssigned = new HashSet<>(mismatch.projectsClaimingDomain());
            claimingButNotAssigned.removeAll(mismatch.orgExpectedProjects());

            if (!claimingButNotAssigned.isEmpty()) {
                String message = """
                        %s is configured in your project but is not assigned to your project in the organization configuration.

                        Please remove it from the domainManagement section of your haus-manager configuration in %%s
                        or submit a PR to update the organization configuration.
                        """
                        .formatted(mismatch.domainName());

                for (var project : claimingButNotAssigned) {
                    createProjectIssueAndMail(title, message,
                            latestProjectConfig.getProjectConfigState(project), true);
                }
            }

            // Email projects that should be claiming but aren't
            Set<String> assignedButNotClaiming = new HashSet<>(mismatch.orgExpectedProjects());
            assignedButNotClaiming.removeAll(mismatch.projectsClaimingDomain());

            if (!assignedButNotClaiming.isEmpty()) {
                // Email each project directly
                String projectMessage = """
                        %s is assigned to your project in the organization configuration but is not present in your project configuration.

                        Please add it to the domainManagement section of your haus-manager configuration in %%s
                        or contact the foundation if this assignment is incorrect.
                        """
                        .formatted(mismatch.domainName());

                for (var project : assignedButNotClaiming) {
                    createProjectIssueAndMail(title, projectMessage,
                            latestProjectConfig.getProjectConfigState(project), true);
                }
            }

            // Also notify org for visibility
            StringBuilder orgMessageBuilder = new StringBuilder();
            orgMessageBuilder.append(
                    "%s has assignment mismatches:\n\n"
                            .formatted(mismatch.domainName()));

            if (!assignedButNotClaiming.isEmpty()) {
                orgMessageBuilder.append(
                        "- Assigned but not claimed: %s\n"
                                .formatted(String.join(", ", assignedButNotClaiming)));
            }

            if (!claimingButNotAssigned.isEmpty()) {
                orgMessageBuilder.append(
                        "- Claiming but not assigned: %s\n"
                                .formatted(String.join(", ", claimingButNotAssigned)));
            }

            orgMessageBuilder.append("""

                    Projects have been notified. Follow up with projects directly to resolve discrepancies.
                    """);

            createOrgIssueAndMail(title, orgMessageBuilder.toString(), true);
        }
    }

    private void handleOrphanDomains(List<DomainReconciliation> orphans) {
        Log.warnf("[%s] %d domains in NameCheap without any configuration:", ME, orphans.size());
        for (var orphan : orphans) {
            Log.warnf("[%s]   %s (expires: %s)", ME,
                    orphan.domainName(), orphan.namecheapInfo().expires());
        }

        String title = "haus-manager: Unconfigured domains in NameCheap";
        String message = """
                The following domains are registered in NameCheap but not present in any organization or project configuration:

                %s

                Please either:
                - Add them to the organization's domainManagement configuration if the foundation manages them
                - Add them to the appropriate project's domainManagement configuration and project assets list
                - Remove them from NameCheap if they're no longer needed
                """
                .formatted(String.join("\n", orphans.stream()
                        .map(o -> "  - " + o.domainName() + " (expires: " + o.namecheapInfo().expires() + ")")
                        .toList()));

        createOrgIssueAndMail(title, message, true);
    }

    private void handleMissingDomains(List<DomainReconciliation> missing) {
        Log.warnf("[%s] %d domains expected but not in NameCheap:", ME, missing.size());

        List<String> details = new ArrayList<>();
        for (var domain : missing) {
            if (domain.orgManagedDirectly) {
                Log.warnf("[%s]   %s (org-managed)", ME, domain.domainName());
                details.add("  - " + domain.domainName() + " (org-managed)");
            } else {
                Log.warnf("[%s]   %s (expected for: %s)", ME,
                        domain.domainName(), String.join(", ", domain.orgExpectedProjects()));
                details.add("  - " + domain.domainName() + " (assigned to: " +
                        String.join(", ", domain.orgExpectedProjects()) + ")");
            }
        }

        String title = "haus-manager: Domains in configuration but not registered";
        String message = """
                The following domains are configured but not found in NameCheap:

                %s

                Please either:
                - Register these domains in NameCheap if they should be managed
                - Remove them from the organization or project configurations if no longer needed
                """
                .formatted(String.join("\n", details));

        createOrgIssueAndMail(title, message, true);
    }

    private void handleValidDomains(List<DomainReconciliation> valid) {
        Log.infof("[%s] Processing %d valid domains for contact sync", ME, valid.size());

        // Send audit email to org with list of valid domains
        if (!valid.isEmpty()) {
            String title = "haus-manager: Domain reconciliation summary";
            String message = """
                    The following %d domains are properly configured and registered:

                    %s

                    These domains will be monitored for contact information updates.
                    """
                    .formatted(
                            valid.size(),
                            String.join("\n", valid.stream()
                                    .map(v -> {
                                        if (v.orgManagedDirectly) {
                                            return "  - " + v.domainName() + " (org-managed)";
                                        } else {
                                            return "  - " + v.domainName() + " (managed by: " +
                                                    String.join(", ", v.projectsClaimingDomain()) + ")";
                                        }
                                    })
                                    .toList()));

            createOrgIssueAndMail(title, message, false);
        }
    }

    /**
     * Convert project name to repository full name using project state lookup.
     * Returns null if project not found.
     */
    private String projectNameToRepoFullName(String projectName) {
        ProjectConfigState state = latestProjectConfig.getProjectStateByName(projectName);
        return state != null ? state.repoFullName() : null;
    }

    private Map<String, DomainReconciliation> reconcileDomainSources(
            List<DomainRecord> namecheapDomains,
            List<ManagedDomain> orgDomains,
            Map<String, Set<String>> orgDomainProjects,
            Map<String, DomainManagementConfig> projectDomainMgmt) {

        Map<String, DomainReconciliation> result = new HashMap<>();

        // 1. Add NameCheap resources
        for (var domainInfo : namecheapDomains) {
            var domain = domainInfo.name();
            result.put(domain, new DomainReconciliation(
                    domain, domainInfo,
                    orgDomains.stream().anyMatch(md -> md.name().equals(domain)),
                    new HashSet<>(), new HashSet<>()));
        }

        // 2. Make sure all org domains are accounted for
        for (var dm : orgDomains) {
            result.computeIfAbsent(dm.name(),
                    k -> new DomainReconciliation(
                            dm.name(), null, true, new HashSet<>(), new HashSet<>()));
        }

        // 2a. Record expected project domains (project assets in org config)
        for (var entry : orgDomainProjects.entrySet()) {
            var domain = entry.getKey();
            var dr = result.computeIfAbsent(domain,
                    k -> new DomainReconciliation(
                            domain, null, false, new HashSet<>(), new HashSet<>()));

            // Convert project names to repo full names for consistent comparison
            for (String projectName : entry.getValue()) {
                String repoFullName = projectNameToRepoFullName(projectName);
                if (repoFullName != null) {
                    dr.orgExpectedProjects.add(repoFullName);
                } else {
                    Log.warnf("[%s] Project '%s' assigned to domain '%s' in org config not found",
                            ME, projectName, domain);
                }
            }
        }

        // 3. Project-declared domain ownership
        for (var entry : projectDomainMgmt.entrySet()) {
            for (var dm : entry.getValue().domains()) {
                var dr = result.computeIfAbsent(dm.name(),
                        k -> new DomainReconciliation(
                                dm.name(), null, false, new HashSet<>(), new HashSet<>()));
                dr.projectsClaimingDomain().add(entry.getKey());
            }
        }

        return result;
    }

    record DomainReconciliation(
            String domainName,
            DomainRecord namecheapInfo, // null if not in NameCheap
            boolean orgManagedDirectly, // org-level domain management, not delegated to projects
            Set<String> orgExpectedProjects, // projects assigned this domain in org config
            Set<String> projectsClaimingDomain // projects with this domain in their config
    ) {
        boolean isInNameCheap() {
            return namecheapInfo != null;
        }

        boolean isInOrgConfig() {
            return orgManagedDirectly || !orgExpectedProjects.isEmpty();
        }

        boolean hasProjectConfig() {
            return !projectsClaimingDomain.isEmpty();
        }

        boolean hasMultipleClaimants() {
            return projectsClaimingDomain.size() > 1;
        }

        boolean hasOrgProjectConflict() {
            // Org managing directly AND project(s) claiming it
            return orgManagedDirectly && hasProjectConfig();
        }

        boolean hasOrgMismatch() {
            return !orgExpectedProjects.equals(projectsClaimingDomain);
        }

        boolean isOrphan() {
            return isInNameCheap() && !isInOrgConfig();
        }
    }

    @Override
    protected String me() {
        return ME;
    }
}
