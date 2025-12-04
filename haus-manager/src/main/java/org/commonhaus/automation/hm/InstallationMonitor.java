package org.commonhaus.automation.hm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.config.RouteSupplier;
import org.commonhaus.automation.github.scopes.ScopedInstallationMap;
import org.commonhaus.automation.hm.ProjectManager.ProjectConfigState;
import org.commonhaus.automation.hm.config.LatestOrgConfig;
import org.commonhaus.automation.hm.config.LatestProjectConfig;
import org.commonhaus.automation.hm.config.ManagerBotConfig;
import org.commonhaus.automation.hm.config.ProjectConfig;
import org.commonhaus.automation.hm.github.AppContextService;
import org.commonhaus.automation.queue.ScheduledService;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class InstallationMonitor extends ScheduledService {
    static final String ME = "🔧-installs";

    @Inject
    AppContextService ctx;

    @Inject
    ManagerBotConfig mgrBotConfig;

    @Inject
    ScopedInstallationMap installationMap;

    @Inject
    LatestOrgConfig latestOrgConfig;

    @Inject
    LatestProjectConfig latestProjectConfig;

    void startup(@Observes StartupEvent startup) {
        RouteSupplier.registerSupplier("Installation check refreshed", () -> lastRun);
    }

    /**
     * Periodically check GitHub organization installations
     */
    // Quartz cron expression: s m h dom mon dow year(optional)
    // Run weekly on Thursday at 1:30 PM (similar to DomainMonitor)
    @Scheduled(cron = "${automation.hausManager.cron.domain:27 13 17 ? * WED *}")
    public void scheduledRefresh() {
        try {
            Log.infof("[%s] ⏰ Scheduled: refresh installation check", ME);
            checkInstallations(false);
        } catch (Throwable t) {
            ctx.logAndSendEmail(ME, "Error running scheduled installation check", t);
        }
    }

    /**
     * Check GitHub organization installations
     *
     * @param userTriggered true if triggered manually
     */
    public void checkInstallations(boolean userTriggered) {
        recordRun();
        if (!latestOrgConfig.getConfig().isOrgValidationEnabled()) {
            Log.infof("[%s]: GitHub organization verification is disabled (last run: %s)", ME, lastRun);
            return;
        }
        if (!userTriggered && !taskState.shouldRun(ME, Duration.ofHours(24))) {
            Log.infof("[%s]: skip installation check (last run: %s)", ME, lastRun);
            return;
        }

        boolean dryRun = latestOrgConfig.getConfig().isOrgValidationDryRun();
        if (dryRun) {
            Log.infof("[%s]: VERIFICATION IN DRY RUN MODE - no emails", ME);
        }

        try {
            // 1. What organizations have installations
            Collection<String> installedOrgs = installationMap.orgs();
            Log.infof("[%s] Retrieved %d installed organization(s)", ME, installedOrgs.size());

            // 2. What the organization config knows about
            Map<String, Set<String>> orgExpectedOrganizations = latestOrgConfig.getConfig()
                    .projects().expectedOrganizations();
            Log.debugf("[%s] Organization config has %d expected organization(s): %s", ME,
                    orgExpectedOrganizations.size(), orgExpectedOrganizations.keySet());

            // 3. What the projects know
            var allProjects = latestProjectConfig.getAllProjects();
            Log.debugf("[%s] All loaded projects (%d): %s", ME, allProjects.size(),
                    allProjects.stream().map(ProjectConfigState::repoFullName)
                            .collect(Collectors.joining(", ")));

            Map<String, Set<String>> projectDeclaredOrganizations = allProjects.stream()
                    .filter(p -> p.projectConfig() != null && !p.githubOrganizations().isEmpty())
                    .collect(Collectors.toMap(
                            ProjectConfigState::repoFullName,
                            p -> new HashSet<>(p.githubOrganizations())));
            Log.debugf("[%s] Projects with GitHub organizations (%d): %s", ME,
                    projectDeclaredOrganizations.size(),
                    String.join(", ", projectDeclaredOrganizations.keySet()));

            // 4. Reconcile sources
            Map<String, InstallationReconciliation> reconciliation = reconcileInstallationSources(
                    installedOrgs,
                    orgExpectedOrganizations,
                    projectDeclaredOrganizations);

            // 5. Process reconciliation results
            processInstallationReconciliation(installedOrgs, reconciliation, dryRun);

        } catch (Exception e) {
            ctx.logAndSendEmail(ME, "Error checking installation information", e);
        }
    }

    private Map<String, InstallationReconciliation> reconcileInstallationSources(
            Collection<String> installedOrgs,
            Map<String, Set<String>> orgExpectedOrganizations,
            Map<String, Set<String>> projectDeclaredOrganizations) {

        Map<String, InstallationReconciliation> result = new HashMap<>();

        // 1. Add all org-expected organizations
        for (var entry : orgExpectedOrganizations.entrySet()) {
            String ghOrg = entry.getKey().replaceAll("^https?://github\\.com/", "");
            Set<String> expectedProjects = entry.getValue();

            // Convert project names to repo full names
            Set<String> expectedProjectRepos = expectedProjects.stream()
                    .map(projectName -> latestOrgConfig.projectNameToRepoFullName(mgrBotConfig, projectName))
                    .collect(Collectors.toSet());

            result.put(ghOrg, new InstallationReconciliation(
                    ghOrg,
                    installedOrgs.contains(ghOrg),
                    expectedProjectRepos,
                    new HashSet<>()));
        }

        // 2. Add project-declared organizations
        for (var entry : projectDeclaredOrganizations.entrySet()) {
            String project = entry.getKey();
            for (String ghOrgKey : entry.getValue()) {
                var ghOrg = ghOrgKey.replaceAll("^https?://github\\.com/", "");
                InstallationReconciliation ir = result.computeIfAbsent(ghOrg,
                        k -> new InstallationReconciliation(
                                ghOrg,
                                installedOrgs.contains(ghOrg),
                                new HashSet<>(),
                                new HashSet<>()));
                ir.projectsDeclaring().add(project);
            }
        }

        return result;
    }

    private void processInstallationReconciliation(
            Collection<String> installedOrgs,
            Map<String, InstallationReconciliation> reconciliation,
            boolean dryRun) {

        // Group issues by project
        Map<String, List<InstallationReconciliation>> issuesByProject = new HashMap<>();

        for (var ir : reconciliation.values()) {
            if (ir.isValid()) {
                continue; // All good, skip
            }

            // Add to each project declaring this org
            for (var project : ir.projectsDeclaring()) {
                issuesByProject.computeIfAbsent(project, k -> new ArrayList<>()).add(ir);
            }

            // Add to projects expected to declare this org
            for (var project : ir.orgExpectedProjects()) {
                issuesByProject.computeIfAbsent(project, k -> new ArrayList<>()).add(ir);
            }
        }

        Log.infof("[%s] Installation reconciliation: %d organizations checked, %d projects with issues",
                ME, reconciliation.size(), issuesByProject.size());

        // Process each project's issues
        for (var entry : issuesByProject.entrySet()) {
            String project = entry.getKey();
            List<InstallationReconciliation> projectIssues = entry.getValue();
            sendProjectInstallationIssues(project, projectIssues, dryRun);
        }

        // Send summary to org (pass installedOrgs to identify unmapped)
        sendOrgSummary(installedOrgs, reconciliation, dryRun);
    }

    private void sendProjectInstallationIssues(
            String project,
            List<InstallationReconciliation> issues,
            boolean dryRun) {

        StringBuilder message = new StringBuilder();

        List<InstallationReconciliation> mismatches = new ArrayList<>();
        List<InstallationReconciliation> notInstalled = new ArrayList<>();

        for (var issue : issues) {
            if (issue.hasMismatch()) {
                mismatches.add(issue);
            } else if (!issue.isInstalled()) {
                notInstalled.add(issue);
            }
        }

        if (!mismatches.isEmpty()) {
            List<String> toAdd = new ArrayList<>();
            List<String> toRemove = new ArrayList<>();

            mismatches.sort(Comparator.comparing(InstallationReconciliation::ghOrgName));
            for (var mismatch : mismatches) {
                if (mismatch.orgExpectedProjects().contains(project) &&
                        !mismatch.projectsDeclaring().contains(project)) {
                    toAdd.add(mismatch.ghOrgName());
                } else if (!mismatch.orgExpectedProjects().contains(project) &&
                        mismatch.projectsDeclaring().contains(project)) {
                    toRemove.add(mismatch.ghOrgName());
                }
            }

            if (!toAdd.isEmpty() || !toRemove.isEmpty()) {
                message.append("## Configuration Mismatches\n\n");
            }

            if (!toAdd.isEmpty()) {
                message.append("**GitHub organizations assigned to your project but not in your configuration:**\n");
                for (var org : toAdd) {
                    message.append("  - ").append(org).append("\n");
                }
                message.append(
                        "\nPlease add these to your `githubOrganizations` list in %s, or create a PR in %s to correct foundation records.\n\n"
                                .formatted(ProjectConfig.PATH, mgrBotConfig.home().repositoryFullName()));
            }

            if (!toRemove.isEmpty()) {
                message.append("**GitHub organizations in your configuration but not assigned to your project:**\n");
                for (var org : toRemove) {
                    message.append("  - ").append(org).append("\n");
                }
                message.append(
                        "\nPlease remove these from your `githubOrganizations` list in %s, or create a PR in %s to correct foundation records.\n\n"
                                .formatted(ProjectConfig.PATH, mgrBotConfig.home().repositoryFullName()));
            }
        }

        if (!notInstalled.isEmpty()) {
            message.append("## Missing Installations\n\n");
            message.append("HausManager is not installed in these GitHub organizations:\n");
            notInstalled.sort(Comparator.comparing(InstallationReconciliation::ghOrgName));
            for (var ni : notInstalled) {
                message.append("  - ").append(ni.ghOrgName()).append("\n");
            }
            message.append("\nPlease install HausManager in these organizations.\n\n");
        }

        if (message.length() == 0) {
            return; // Nothing to send
        }

        String title = "haus-manager: GitHub organization issues for " + project;
        ProjectConfigState state = latestProjectConfig.getProjectConfigState(project);

        if (dryRun) {
            Log.infof("[%s] DRY RUN: would send email for project %s. title: %s; body: %s",
                    ME, project, title, message);
        } else if (state != null && state.projectConfig() != null) {
            ctx.sendEmail(ME, title, message.toString(),
                    state.projectConfig().emailNotifications().errors());
        }

        // cc: send a copy to org errors email
        ctx.sendEmail(ME, title, message.toString(),
                dryRun
                        ? latestOrgConfig.getConfig().emailNotifications().dryRun()
                        : latestOrgConfig.getConfig().emailNotifications().errors());
    }

    private void sendOrgSummary(
            Collection<String> installedOrgs,
            Map<String, InstallationReconciliation> reconciliation,
            boolean dryRun) {

        List<InstallationReconciliation> valid = reconciliation.values().stream()
                .filter(InstallationReconciliation::isValid)
                .sorted(Comparator.comparing(InstallationReconciliation::ghOrgName))
                .toList();

        // Find unmapped organizations (installed but not in any config)
        Set<String> unmappedOrgs = new HashSet<>(installedOrgs);
        unmappedOrgs.removeAll(reconciliation.keySet());

        if (valid.isEmpty() && unmappedOrgs.isEmpty()) {
            return; // Nothing to report
        }

        StringBuilder message = new StringBuilder();

        if (!valid.isEmpty()) {
            message.append("## Configured Organizations\n\n");
            message.append(
                    "The following %d GitHub organizations are properly configured and have HausManager installed:\n\n"
                            .formatted(valid.size()));
            for (var v : valid) {
                if (!v.orgExpectedProjects().isEmpty()) {
                    message.append("  - ").append(v.ghOrgName()).append(" (assigned to: ")
                            .append(String.join(", ", v.orgExpectedProjects())).append(")\n");
                } else {
                    message.append("  - ").append(v.ghOrgName()).append(" (declared by: ")
                            .append(String.join(", ", v.projectsDeclaring())).append(")\n");
                }
            }
            message.append("\n");
        }

        if (!unmappedOrgs.isEmpty()) {
            message.append("## Unmapped Organizations\n\n");
            message.append(
                    "The following %d GitHub organizations have HausManager installed but are not mapped to any project:\n\n"
                            .formatted(unmappedOrgs.size()));
            for (var org : unmappedOrgs.stream().sorted().toList()) {
                message.append("  - ").append(org).append("\n");
            }
            message.append(
                    "\nConsider adding these to the organization config or removing the installation if not needed.\n");
        }

        String title = "haus-manager: GitHub organization verification summary";

        ctx.sendEmail(ME, title, message.toString(),
                dryRun
                        ? latestOrgConfig.getConfig().emailNotifications().dryRun()
                        : latestOrgConfig.getConfig().emailNotifications().audit());
    }

    record InstallationReconciliation(
            String ghOrgName,
            boolean isInstalled,
            Set<String> orgExpectedProjects, // projects assigned this ghOrg in org config
            Set<String> projectsDeclaring // projects declaring this ghOrg in their config
    ) {
        boolean hasMismatch() {
            return !orgExpectedProjects.equals(projectsDeclaring);
        }

        boolean isValid() {
            return isInstalled && !hasMismatch();
        }
    }

    @Override
    protected String me() {
        return ME;
    }
}
