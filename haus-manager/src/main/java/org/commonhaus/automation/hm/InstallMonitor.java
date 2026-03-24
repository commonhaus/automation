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
import java.util.TreeMap;
import java.util.TreeSet;
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
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class InstallMonitor extends ScheduledService {
    static final String ME = "🔧-installs";

    @CheckedTemplate
    static class Templates {
        public static native TemplateInstance projectInstallationIssues(
                Set<String> toAdd,
                Set<String> toRemove,
                Set<String> notInstalled,
                String projectConfigPath,
                String homeRepo);

        public static native TemplateInstance orgSummary(
                List<ProjectOrgGroup> orgGroups,
                List<String> unmapped);
    }

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
    // Run at 17:13:27, on every Wednesday, every month
    @Scheduled(cron = "${automation.hausManager.cron.install:27 13 17 ? * WED *}")
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

        // 1. Seed org-managed organizations (from githubOrganizations field)
        for (String ghOrgKey : latestOrgConfig.getConfig().githubOrganizations()) {
            String ghOrg = normalizeOrg(ghOrgKey);
            result.put(ghOrg, new InstallationReconciliation(
                    ghOrg,
                    installedOrgs.contains(ghOrg),
                    true,
                    new HashSet<>(),
                    new HashSet<>()));
        }

        // 2. Add all org-expected organizations (from projects section of org config)
        for (var entry : orgExpectedOrganizations.entrySet()) {
            String ghOrg = normalizeOrg(entry.getKey());
            Set<String> expectedProjects = entry.getValue();

            // Convert project names to repo full names
            Set<String> expectedProjectRepos = expectedProjects.stream()
                    .map(projectName -> latestOrgConfig.projectNameToRepoFullName(mgrBotConfig, projectName))
                    .collect(Collectors.toSet());

            result.computeIfAbsent(ghOrg, k -> new InstallationReconciliation(
                    ghOrg,
                    installedOrgs.contains(ghOrg),
                    false,
                    new HashSet<>(),
                    new HashSet<>()))
                    .orgExpectedProjects().addAll(expectedProjectRepos);
        }

        // 3. Add project-declared organizations
        for (var entry : projectDeclaredOrganizations.entrySet()) {
            String project = entry.getKey();
            for (String ghOrgKey : entry.getValue()) {
                var ghOrg = this.normalizeOrg(ghOrgKey);
                InstallationReconciliation ir = result.computeIfAbsent(ghOrg,
                        k -> new InstallationReconciliation(
                                ghOrg,
                                installedOrgs.contains(ghOrg),
                                false,
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

        // Group project-actionable issues by project
        Map<String, Set<InstallationReconciliation>> issuesByProject = new HashMap<>();

        for (var ir : reconciliation.values()) {
            if (ir.isValid() || ir.orgManagedDirectly() || ir.isOrphan()) {
                continue; // Org-level concerns are visible in the audit summary
            }

            for (var project : ir.projectsDeclaring()) {
                issuesByProject.computeIfAbsent(project, k -> new HashSet<>()).add(ir);
            }

            for (var project : ir.orgExpectedProjects()) {
                issuesByProject.computeIfAbsent(project, k -> new HashSet<>()).add(ir);
            }
        }

        Log.infof("[%s] Installation reconciliation: %d organizations checked, %d projects with issues",
                ME, reconciliation.size(), issuesByProject.size());

        // Send per-project error emails
        for (var entry : issuesByProject.entrySet()) {
            sendProjectInstallationIssues(entry.getKey(), entry.getValue(), dryRun);
        }

        // Send structured audit summary
        sendOrgSummary(installedOrgs, reconciliation, dryRun);
    }

    private void sendProjectInstallationIssues(
            String project,
            Set<InstallationReconciliation> issues,
            boolean dryRun) {

        Set<String> toAdd = new TreeSet<>();
        Set<String> toRemove = new TreeSet<>();
        Set<String> notInstalled = new TreeSet<>();

        for (var ir : issues) {
            if (ir.hasOrgMismatch()) {
                if (ir.orgExpectedProjects().contains(project) &&
                        !ir.projectsDeclaring().contains(project)) {
                    toAdd.add(ir.ghOrgName());
                } else if (!ir.orgExpectedProjects().contains(project) &&
                        ir.projectsDeclaring().contains(project)) {
                    toRemove.add(ir.ghOrgName());
                }
            }
            if (!ir.isInstalled()) {
                notInstalled.add(ir.ghOrgName());
            }
        }

        if (toAdd.isEmpty() && toRemove.isEmpty() && notInstalled.isEmpty()) {
            return;
        }

        String message = Templates.projectInstallationIssues(
                toAdd,
                toRemove,
                notInstalled,
                ProjectConfig.PATH,
                mgrBotConfig.home().repositoryFullName()).render();

        // Extract display name from repo full name (e.g., "easymock" from "org/project-easymock")
        String projectDisplayName = latestOrgConfig.getProjectDisplayNameFromRepo(project);
        String title = "haus-manager: GitHub organization issues for " + projectDisplayName;
        ProjectConfigState state = latestProjectConfig.getProjectConfigState(project);

        if (dryRun) {
            Log.infof("[%s] DRY RUN: would send email for project %s. title: %s; body: %s",
                    ME, project, title, message);
            ctx.sendEmail(ME, title, message, latestOrgConfig.getConfig().emailNotifications().dryRun());
        } else if (state != null && state.projectConfig() != null) {
            ctx.sendEmail(ME, title, message,
                    state.projectConfig().emailNotifications().errors());
            // CC to org errors
            ctx.sendEmail(ME, title, message, latestOrgConfig.getConfig().emailNotifications().errors());
        }
    }

    private void sendOrgSummary(
            Collection<String> installedOrgs,
            Map<String, InstallationReconciliation> reconciliation,
            boolean dryRun) {

        Set<OrgStatus> orgManagedList = new TreeSet<>(Comparator.comparing(OrgStatus::ghOrgName));
        // Collect known org-config orgs while building project groups (single pass over assets)
        Map<String, Set<OrgStatus>> projectOrgMap = new TreeMap<>();
        Set<String> knownOrgs = new HashSet<>();

        var projectAssets = latestOrgConfig.getConfig().projects();
        if (projectAssets != null) {
            for (var entry : projectAssets.allAssets().entrySet()) {
                String projectName = entry.getKey();
                Set<OrgStatus> orgStatuses = new TreeSet<>(Comparator.comparing(OrgStatus::ghOrgName));
                for (String ghOrgKey : entry.getValue().githubOrganizations()) {
                    String ghOrg = normalizeOrg(ghOrgKey);
                    knownOrgs.add(ghOrg);
                    InstallationReconciliation ir = reconciliation.get(ghOrg);
                    if (ir != null) {
                        orgStatuses.add(new OrgStatus(ir.ghOrgName(), ir.isInstalled(), ir.hasConfigIssues()));
                    } else {
                        orgStatuses.add(new OrgStatus(ghOrg, false, true));
                    }
                }
                projectOrgMap.put(projectName, orgStatuses);
            }
        }

        // Classify reconciliation entries: org-managed and project-only
        for (var ir : reconciliation.values()) {
            if (ir.orgManagedDirectly()) {
                knownOrgs.add(ir.ghOrgName());
                orgManagedList.add(new OrgStatus(ir.ghOrgName(), ir.isInstalled(), ir.hasConfigIssues()));
            } else if (!knownOrgs.contains(ir.ghOrgName()) && !ir.projectsDeclaring().isEmpty()) {
                // Declared by project but not in org config
                for (String repoFullName : ir.projectsDeclaring()) {
                    String projectName = latestOrgConfig.getProjectDisplayNameFromRepo(repoFullName);
                    projectOrgMap.computeIfAbsent(projectName, k -> new TreeSet<>(Comparator.comparing(OrgStatus::ghOrgName)))
                            .add(new OrgStatus(ir.ghOrgName(), ir.isInstalled(), true, true));
                }
            }
        }

        // Build ordered group list
        List<ProjectOrgGroup> orgGroups = new ArrayList<>();
        if (!orgManagedList.isEmpty()) {
            orgGroups.add(new ProjectOrgGroup("Organization", orgManagedList));
        }
        for (var entry : projectOrgMap.entrySet()) {
            Set<OrgStatus> orgs = entry.getValue();
            if (!orgs.isEmpty()) {
                orgGroups.add(new ProjectOrgGroup(entry.getKey(), orgs));
            }
        }

        // Find unmapped organizations (installed but not in any config)
        Set<String> unmappedOrgs = new HashSet<>(installedOrgs);
        unmappedOrgs.removeAll(reconciliation.keySet());
        List<String> unmappedList = unmappedOrgs.stream().sorted().toList();

        String message = Templates.orgSummary(orgGroups, unmappedList).render();
        String title = "haus-manager: GitHub organization verification summary";

        ctx.sendEmail(ME, title, message,
                dryRun
                        ? latestOrgConfig.getConfig().emailNotifications().dryRun()
                        : latestOrgConfig.getConfig().emailNotifications().audit());
    }

    record InstallationReconciliation(
            String ghOrgName,
            boolean isInstalled,
            boolean orgManagedDirectly,
            Set<String> orgExpectedProjects,
            Set<String> projectsDeclaring) {

        boolean hasOrgProjectConflict() {
            return orgManagedDirectly && !projectsDeclaring.isEmpty();
        }

        boolean hasOrgMismatch() {
            return !orgManagedDirectly && !orgExpectedProjects.equals(projectsDeclaring);
        }

        boolean isOrphan() {
            return isInstalled && !orgManagedDirectly
                    && orgExpectedProjects.isEmpty() && projectsDeclaring.isEmpty();
        }

        /** Has configuration issues independent of installation status */
        boolean hasConfigIssues() {
            return hasOrgProjectConflict() || hasOrgMismatch();
        }

        boolean isValid() {
            return isInstalled && !hasConfigIssues() && !isOrphan();
        }
    }

    record OrgStatus(String ghOrgName, boolean isInstalled, boolean hasConfigIssues, boolean isMissingFromOrgConfig) {
        OrgStatus(String ghOrgName, boolean isInstalled, boolean hasConfigIssues) {
            this(ghOrgName, isInstalled, hasConfigIssues, false);
        }
    }

    record ProjectOrgGroup(String projectName, Set<OrgStatus> organizations) {
    }

    String normalizeOrg(String ghOrg) {
        return ghOrg.replaceAll("^https?://github\\.com/", "");
    }

    @Override
    protected String me() {
        return ME;
    }
}
