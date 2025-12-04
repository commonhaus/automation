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
import org.commonhaus.automation.hm.namecheap.NamecheapException;
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
    static final String ME = "🌐-domains";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Inject
    AppContextService ctx;

    @Inject
    public ManagerBotConfig mgrBotConfig;

    @Inject
    PeriodicUpdateQueue updateQueue;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    NamecheapService namecheapService;

    @Inject
    public LatestOrgConfig latestOrgConfig;

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
            ctx.logAndSendEmail(ME, "Error running scheduled domain refresh", t);
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
            var allProjects = latestProjectConfig.getAllProjects();
            Log.debugf("[%s] All loaded projects (%d): %s", ME, allProjects.size(),
                    allProjects.stream().map(p -> p.repoFullName()).collect(Collectors.joining(", ")));
            var projectDomainsMgmt = allProjects.stream()
                    .filter(p -> p.projectConfig() != null
                            && p.projectConfig().domainManagement() != null
                            && p.projectConfig().domainManagement().isEnabled())
                    .collect(Collectors.toMap(
                            p -> p.repoFullName(),
                            p -> p.projectConfig().domainManagement()));
            Log.debugf("[%s] Projects with domain management (%d): %s", ME, projectDomainsMgmt.size(),
                    String.join(", ", projectDomainsMgmt.keySet()));

            // 4. Reconcile sources
            // Note: Include org domains in reconciliation even if management is disabled
            // The enabled flag only controls whether we actively sync contacts
            var domainReconciliation = reconcileDomainSources(
                    namecheapDomains,
                    orgDomainMgmt.domains(),
                    orgDomainProject,
                    projectDomainsMgmt);

            // 5. Process reconciliation results
            var validDomains = processDomainReconciliation(domainReconciliation);

            // 6. Synchronize contacts for valid domains (only if management is enabled)
            for (var validDomain : validDomains) {
                if (validDomain.orgManagedDirectly) {
                    // Only sync if org domain management is enabled
                    if (orgDomainMgmt.isEnabled()) {
                        syncContactsForProject(
                                mgrBotConfig.home().repositoryFullName(),
                                orgDomainMgmt,
                                latestOrgConfig.getConfig().emailNotifications());
                    } else {
                        Log.debugf("[%s] Skipping contact sync for org domain %s (management disabled)",
                                ME, validDomain.domainName());
                    }
                } else {
                    // valid domains will have a set with exactly one project
                    var project = validDomain.projectsClaimingDomain().iterator().next();
                    var projectDomainMgmt = projectDomainsMgmt.get(project);
                    // Project domain management is already filtered by isEnabled() at line 128
                    syncContactsForProject(
                            project,
                            projectDomainMgmt,
                            latestProjectConfig.getProjectConfigState(project).emailNotifications());
                }
            }

            // 7. Dispatch full domain list to GitHub Actions workflow for reporting
            if (!dryRun) {
                dispatchDomainList(namecheapDomains);
            }
        } catch (NamecheapException e) {
            ctx.logAndSendEmail(ME, "Error interacting with Namecheap", e);
        } catch (Exception e) {
            ctx.logAndSendEmail(ME, "Error refreshing domain information", e);
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
        DomainContacts currentContacts;
        try {
            Optional<DomainContacts> contactsOpt = namecheapService.getContacts(domainName);
            if (contactsOpt.isEmpty()) {
                Log.errorf("[%s] No contacts returned for %s", ME, domainName);
                return;
            }
            currentContacts = contactsOpt.get();
        } catch (Exception e) {
            ctx.logAndSendEmail(ME,
                    "Error syncing contacts for domain: " + domainName, e, emailNotification.errors());
            return;
        }

        // Build desired contacts by merging: bot defaults + project tech contact +
        // domain-specific tech contact
        // Preserve contact type flags from current contacts (which types the TLD
        // supports)
        DomainContacts desiredContacts = buildDesiredContacts(
                managedDomain, domainConfig, defaultContacts, currentContacts, emailNotification);

        // Is update required?
        if (!desiredContacts.requiresUpdate(currentContacts)) {
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

                            """.formatted(domainName, currentContacts.prettyString(),
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

                                """.formatted(domainName, currentContacts.prettyString(),
                                desiredContacts.prettyString()),
                        emailNotification.audit());
            } else {
                ctx.logAndSendEmail(ME, "Failed to update contacts for " + domainName, null);
            }
        } catch (Exception e) {
            ctx.logAndSendEmail(ME,
                    "Error syncing contacts for domain: " + domainName, e, emailNotification.errors());
            return;
        }
    }

    /**
     * Build desired contacts by merging bot defaults with project/domain tech contact overrides.
     * Tech contact hierarchy: domain-specific > project-level > bot default
     *
     * Project contacts can specify minimal information (firstName, lastName, emailAddress)
     * and optionally override phone and/or address fields. Missing fields are filled from
     * bot defaults via merging.
     *
     * Validation rules:
     * - Always required: firstName, lastName, emailAddress
     * - Phone: optional, but if specified must be valid format
     * - Address: optional as group, but if any address field specified, all must be specified
     *
     * Preserves contact type flags from currentContacts to ensure we only update
     * contact types that the TLD actually supports.
     */
    private DomainContacts buildDesiredContacts(ManagedDomain managedDomain,
            DomainManagementConfig domainConfig, DomainContacts defaultContacts,
            DomainContacts currentContacts, EmailNotification emailNotification) {

        ContactInfo registrant = defaultContacts.registrant();
        ContactInfo admin = defaultContacts.admin();
        ContactInfo billing = defaultContacts.auxBilling();

        // Start with tech contact from bot defaults
        ContactInfo tech = defaultContacts.tech();

        // Check for domain-specific tech contact override (highest priority)
        if (managedDomain.techContact().map(c -> c.isValid(ctx, ME, managedDomain.name(), emailNotification))
                .orElse(false)) {
            tech = ContactInfo.fromConfig(tech, managedDomain.techContact().get());
            Log.debugf("[%s] Merging domain-specific tech contact for %s", ME, managedDomain.name());
        }
        // Check for project-level tech contact override (medium priority)
        else if (domainConfig.getTechContact().map(c -> c.isValid(ctx, ME, managedDomain.name(), emailNotification))
                .orElse(false)) {
            tech = ContactInfo.fromConfig(tech, domainConfig.getTechContact().get());
            Log.debugf("[%s] Merging project-level tech contact for %s", ME, managedDomain.name());
        }

        // Preserve contact type flags from current contacts (which contact types the
        // TLD supports)
        return new DomainContacts(registrant, tech, admin, billing,
                currentContacts.hasTech(),
                currentContacts.hasAdmin(),
                currentContacts.hasAuxBilling());
    }

    /**
     * Process domain reconciliation results and take appropriate actions
     */
    private List<DomainReconciliation> processDomainReconciliation(Map<String, DomainReconciliation> reconciliation) {

        // Group domain issues by project - each project can have multiple domain issues
        Map<String, List<DomainReconciliation>> issuesByProject = new HashMap<>();
        List<DomainReconciliation> validDomains = new ArrayList<>();

        for (var dr : reconciliation.values()) {
            // Collect valid domains separately for contact sync
            if (dr.isValid()) {
                validDomains.add(dr);
                continue;
            }
            // Add to org for domains it manages directly or orphans
            if (dr.orgManagedDirectly || dr.isOrphan()) {
                String orgRepo = mgrBotConfig.home().repositoryFullName();
                issuesByProject.computeIfAbsent(orgRepo, k -> new ArrayList<>()).add(dr);
            }
            // Add to each project claiming this domain
            for (var project : dr.projectsClaimingDomain()) {
                issuesByProject.computeIfAbsent(project, k -> new ArrayList<>()).add(dr);
            }
            // Add to projects that should claim this domain but don't
            for (var project : dr.orgExpectedProjects()) {
                issuesByProject.computeIfAbsent(project, k -> new ArrayList<>()).add(dr);
            }
        }

        // Log summary
        Log.infof("[%s] Domain reconciliation: %d results, %d valid domains", ME, reconciliation.size(),
                validDomains.size());

        // Process each project's domain issues
        for (var entry : issuesByProject.entrySet()) {
            String project = entry.getKey();
            List<DomainReconciliation> projectDomains = entry.getValue();

            Log.debugf("[%s] Project %s has %d domain issue(s)", ME, project, projectDomains.size());

            // Categorize this project's domains
            List<DomainReconciliation> orgProjectConflicts = new ArrayList<>();
            List<DomainReconciliation> multipleProjectConflicts = new ArrayList<>();
            List<DomainReconciliation> projectMismatches = new ArrayList<>();
            List<DomainReconciliation> orphanDomains = new ArrayList<>();
            List<DomainReconciliation> missingFromNamecheap = new ArrayList<>();

            for (var recon : projectDomains) {
                if (recon.hasOrgProjectConflict()) {
                    orgProjectConflicts.add(recon);
                } else if (recon.hasMultipleClaimants()) {
                    multipleProjectConflicts.add(recon);
                } else if (recon.hasOrgMismatch()) {
                    projectMismatches.add(recon);
                } else if (recon.isOrphan()) {
                    orphanDomains.add(recon);
                } else if (recon.isInOrgConfig() && !recon.isInNameCheap()) {
                    missingFromNamecheap.add(recon);
                } else {
                    ctx.logAndSendEmail(ME, "Unknown domain reconciliation states",
                            "[%s] Uncategorized domain %s: inNC=%s, orgManaged=%s, orgExpected=%s, projClaims=%s"
                                    .formatted(
                                            ME, recon.domainName(), recon.isInNameCheap(), recon.orgManagedDirectly,
                                            recon.orgExpectedProjects(), recon.projectsClaimingDomain()),
                            null,
                            latestOrgConfig.getConfig().emailNotifications().errors());
                }
            }

            sendProjectDomainIssues(project, orgProjectConflicts, multipleProjectConflicts,
                    projectMismatches, orphanDomains, missingFromNamecheap);
        }

        // Send summary of valid domains to org
        if (!validDomains.isEmpty()) {
            handleValidDomains(validDomains);
        }

        return validDomains;
    }

    /**
     * Send consolidated domain issue notification to a project
     */
    private void sendProjectDomainIssues(String project,
            List<DomainReconciliation> orgProjectConflicts,
            List<DomainReconciliation> multipleProjectConflicts,
            List<DomainReconciliation> projectMismatches,
            List<DomainReconciliation> orphanDomains,
            List<DomainReconciliation> missingFromNamecheap) {

        // Check if this is the org repo
        boolean isOrgRepo = project.equals(mgrBotConfig.home().repositoryFullName());

        // Build consolidated message
        StringBuilder message = new StringBuilder();

        if (!orgProjectConflicts.isEmpty()) {
            message.append("## Conflicts with Organization Management\n\n");
            if (isOrgRepo) {
                message.append(
                        "These domains are managed directly by the organization but are also claimed by projects:\n");
            } else {
                message.append(
                        "These domains are managed by the organization but are also in your project configuration:\n");
            }
            orgProjectConflicts.sort((dr1, dr2) -> dr1.domainName().compareTo(dr2.domainName()));
            for (var conflict : orgProjectConflicts) {
                message.append("  - ").append(conflict.domainName());
                if (isOrgRepo) {
                    message.append(" (also claimed by: ")
                            .append(String.join(", ", conflict.projectsClaimingDomain()));
                }
                message.append(")\n");
            }
            if (!isOrgRepo) {
                message.append("\nPlease remove these from your domainManagement configuration.\n");
            }
            message.append("\n");
        }

        if (!multipleProjectConflicts.isEmpty()) {
            message.append("## Conflicts with Other Projects\n\n");
            message.append("These domains are claimed by multiple projects:\n");
            multipleProjectConflicts.sort((dr1, dr2) -> dr1.domainName().compareTo(dr2.domainName()));
            for (var conflict : multipleProjectConflicts) {
                message.append("  - ").append(conflict.domainName())
                        .append(" (also claimed by: ")
                        .append(String.join(", ", conflict.projectsClaimingDomain()))
                        .append(")\n");
            }
            message.append("\nPlease coordinate with other projects to resolve ownership.\n\n");
        }

        if (!projectMismatches.isEmpty()) {
            message.append("## Configuration Mismatches\n\n");
            List<String> toAdd = new ArrayList<>();
            List<String> toRemove = new ArrayList<>();

            projectMismatches.sort((dr1, dr2) -> dr1.domainName().compareTo(dr2.domainName()));
            for (var mismatch : projectMismatches) {
                if (mismatch.orgExpectedProjects().contains(project) &&
                        !mismatch.projectsClaimingDomain().contains(project)) {
                    toAdd.add(mismatch.domainName());
                } else if (!mismatch.orgExpectedProjects().contains(project) &&
                        mismatch.projectsClaimingDomain().contains(project)) {
                    toRemove.add(mismatch.domainName());
                }
            }

            if (!toAdd.isEmpty()) {
                message.append("**Domains assigned to your project but not in your configuration:**\n");
                for (var domain : toAdd) {
                    message.append("  - ").append(domain).append("\n");
                }
                message.append(
                        "\nPlease add these to your domainManagement configuration, or create a PR in %s to correct foundation records..\n\n"
                                .formatted(mgrBotConfig.home().repositoryFullName()));
            }

            if (!toRemove.isEmpty()) {
                message.append("**Domains in your configuration but not assigned to your project:**\n");
                for (var domain : toRemove) {
                    message.append("  - ").append(domain).append("\n");
                }
                message.append(
                        "\nPlease remove these from your domainManagement configuration, or create a PR in %s to correct foundation records.\n\n"
                                .formatted(mgrBotConfig.home().repositoryFullName()));
            }
        }

        if (!orphanDomains.isEmpty() && isOrgRepo) {
            message.append("## Unconfigured Domains\n\n");
            message.append("These domains are registered but not configured:\n");
            orphanDomains.sort((dr1, dr2) -> dr1.domainName().compareTo(dr2.domainName()));
            for (var orphan : orphanDomains) {
                message.append("  - ").append(orphan.domainName())
                        .append(" (expires: ").append(orphan.namecheapInfo().expires()).append(")\n");
            }
            message.append("\nPlease add to organization or project configuration, or remove from Namecheap.\n\n");
        }

        if (!missingFromNamecheap.isEmpty()) {
            message.append("## Domains Not Registered\n\n");
            message.append("These domains are in configuration but not registered in Namecheap:\n");
            missingFromNamecheap.sort((dr1, dr2) -> dr1.domainName().compareTo(dr2.domainName()));
            for (var missing : missingFromNamecheap) {
                message.append("  - ").append(missing.domainName()).append("\n");
            }
            message.append("\nPlease register these domains or remove from configuration.\n\n");
        }

        // Send the notification using helper methods
        String title = isOrgRepo
                ? "haus-manager: Domain issues for organization"
                : "haus-manager: Domain issues for " + project;

        if (isOrgRepo) {
            createOrgIssueAndMail(title, message.toString(), true);
        } else {
            ProjectConfigState state = latestProjectConfig.getProjectConfigState(project);
            createProjectIssueAndMail(title, message.toString(), state, true);
            // cc: send a copy to org errors email
            createOrgIssueAndMail(title, message.toString(), true);
        }
    }

    private void createProjectIssueAndMail(String title, String messageFormat,
            ProjectConfigState state, boolean isError) {

        // Skip if project doesn't have a valid configuration
        if (state == null || state.projectConfig() == null) {
            Log.warnf("[%s] Cannot send notification for project (no config found). title: %s",
                    ME, title);
            return;
        }

        var addresses = isError
                ? state.projectConfig().emailNotifications().errors()
                : state.projectConfig().emailNotifications().audit();
        var message = messageFormat.formatted(state.repoFullName());

        if (latestOrgConfig.getConfig().isMonitoringDryRun() || state.isDomainManagementDryRun()) {
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
            addresses = latestOrgConfig.getConfig().emailNotifications().dryRun();
        }

        ctx.sendEmail(ME, title, message, addresses);

        // TODO: Create if absent
        // ReportQueryContext rqc =
        // ctx.getReportQueryContext(mgrBotConfig.home().repositoryFullName());
        // rqc.createItem(EventType.issue, title, message, null);
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
                                    .sorted((v1, v2) -> v1.domainName().compareTo(v2.domainName()))
                                    .map(v -> {
                                        if (v.orgManagedDirectly) {
                                            return "  - " + v.domainName() + " (expires " + v.namecheapInfo().expires()
                                                    + "; org-managed)";
                                        } else {
                                            return "  - " + v.domainName() + " (expires " + v.namecheapInfo().expires()
                                                    + "; managed by: " +
                                                    String.join(", ", v.projectsClaimingDomain()) + ")";
                                        }
                                    })
                                    .toList()));

            createOrgIssueAndMail(title, message, false);
        }
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
                String repoFullName = latestOrgConfig.projectNameToRepoFullName(mgrBotConfig, projectName);
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
            // Multiple projects claiming same domain = conflict
            return projectsClaimingDomain.size() > 1;
        }

        boolean hasOrgProjectConflict() {
            // Org managing directly AND project(s) claiming it
            return orgManagedDirectly && hasProjectConfig();
        }

        boolean hasOrgMismatch() {
            // Project claiming domain not assigned to them in org config
            // or vice versa
            return !orgExpectedProjects.equals(projectsClaimingDomain);
        }

        boolean isOrphan() {
            // In NameCheap but not in any config (org or project)
            return isInNameCheap() && !isInOrgConfig();
        }

        boolean isValid() {
            return isInNameCheap() && isInOrgConfig()
                    && (orgManagedDirectly || projectsClaimingDomain.size() == 1)
                    && !hasOrgMismatch();
        }
    }

    @Override
    protected String me() {
        return ME;
    }
}
