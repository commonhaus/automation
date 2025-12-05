package org.commonhaus.automation.hm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.commonhaus.automation.hm.ProjectManager.ProjectConfigState;
import org.commonhaus.automation.hm.config.LatestOrgConfig;
import org.commonhaus.automation.hm.config.LatestProjectConfig;
import org.commonhaus.automation.hm.config.ManagedDomain;
import org.commonhaus.automation.hm.config.ManagerBotConfig;
import org.commonhaus.automation.hm.github.AppContextService;
import org.commonhaus.automation.queue.ScheduledService;

import io.quarkus.logging.Log;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;

@ApplicationScoped
public class AnnualAssetReport extends ScheduledService {
    static final String ME = "📋-annual";

    @CheckedTemplate
    static class Templates {
        public static native TemplateInstance annualReport(
                String projectName,
                String repoFullName,
                String techContact,
                List<String> matchedDomains,
                List<String> matchedGithubOrgs,
                List<AssetMismatch> mismatches,
                String homeRepo);
    }

    @Inject
    AppContextService ctx;

    @Inject
    ManagerBotConfig mgrBotConfig;

    @Inject
    LatestOrgConfig latestOrgConfig;

    @Inject
    LatestProjectConfig latestProjectConfig;

    /**
     * Generate and send annual asset reports to all projects
     *
     * @param dryRun if true, log what would be sent instead of actually sending emails
     */
    public void generateAnnualReports(boolean dryRun) {
        recordRun();

        if (dryRun) {
            Log.infof("[%s] DRY RUN: Generating annual asset reports (no emails will be sent)", ME);
        } else {
            Log.infof("[%s] Generating annual asset reports", ME);
        }

        try {
            // 1. Get org-expected assets
            var orgExpectedDomains = latestOrgConfig.getConfig().projects().expectedDomains();
            var orgExpectedOrgs = latestOrgConfig.getConfig().projects().expectedOrganizations();

            // 2. Get all projects
            var allProjects = latestProjectConfig.getAllProjects();
            Log.infof("[%s] Processing %d project(s)", ME, allProjects.size());

            // 3. Reconcile each project
            List<ProjectAssetReconciliation> reconciliations = new ArrayList<>();
            for (ProjectConfigState projectState : allProjects) {
                if (projectState == null || projectState.projectConfig() == null) {
                    continue; // Skip projects without valid config
                }

                ProjectAssetReconciliation recon = reconcileProjectAssets(
                        projectState, orgExpectedDomains, orgExpectedOrgs);
                reconciliations.add(recon);
            }

            // 4. Send reports
            for (ProjectAssetReconciliation recon : reconciliations) {
                try {
                    sendAnnualReport(recon, dryRun);
                } catch (Exception e) {
                    ctx.logAndSendEmail(ME,
                            "Error sending annual report for project: " + recon.repoFullName(), e);
                    // Continue processing other projects
                }
            }

            Log.infof("[%s] Annual reports sent for %d project(s)", ME, reconciliations.size());

        } catch (Exception e) {
            ctx.logAndSendEmail(ME, "Error generating annual asset reports", e);
        }
    }

    /**
     * Reconcile a project's declared assets with the organization's expected assets
     */
    private ProjectAssetReconciliation reconcileProjectAssets(
            ProjectConfigState projectState,
            Map<String, Set<String>> orgExpectedDomains,
            Map<String, Set<String>> orgExpectedOrgs) {

        String repoFullName = projectState.repoFullName();
        String projectName = extractProjectName(repoFullName);

        // Get project's declared assets
        var projectConfig = projectState.projectConfig();
        Set<String> projectDomains = projectConfig.domainManagement() != null
                && projectConfig.domainManagement().isEnabled()
                        ? projectConfig.domainManagement().domains().stream()
                                .map(ManagedDomain::name)
                                .collect(Collectors.toSet())
                        : Set.of();

        Set<String> projectGhOrgs = new HashSet<>(projectState.githubOrganizations());

        // Get org's expected assets for this project
        Set<String> orgExpectedDomainsForProject = getExpectedAssetsForProject(
                projectName, repoFullName, orgExpectedDomains);
        Set<String> orgExpectedOrgsForProject = getExpectedAssetsForProject(
                projectName, repoFullName, orgExpectedOrgs);

        // Reconcile domains
        Set<String> matchedDomains = new HashSet<>(projectDomains);
        matchedDomains.retainAll(orgExpectedDomainsForProject);

        Set<String> domainsInProjectNotOrg = new HashSet<>(projectDomains);
        domainsInProjectNotOrg.removeAll(orgExpectedDomainsForProject);

        Set<String> domainsInOrgNotProject = new HashSet<>(orgExpectedDomainsForProject);
        domainsInOrgNotProject.removeAll(projectDomains);

        // Reconcile GitHub orgs
        Set<String> matchedOrgs = new HashSet<>(projectGhOrgs);
        matchedOrgs.retainAll(orgExpectedOrgsForProject);

        Set<String> orgsInProjectNotOrg = new HashSet<>(projectGhOrgs);
        orgsInProjectNotOrg.removeAll(orgExpectedOrgsForProject);

        Set<String> orgsInOrgNotProject = new HashSet<>(orgExpectedOrgsForProject);
        orgsInOrgNotProject.removeAll(projectGhOrgs);

        // Build mismatch list
        List<AssetMismatch> mismatches = new ArrayList<>();

        for (String domain : domainsInProjectNotOrg) {
            mismatches.add(new AssetMismatch(
                    AssetType.DOMAIN, domain, MismatchType.IN_PROJECT_NOT_IN_ORG,
                    "Declared in project but not registered with foundation"));
        }

        for (String domain : domainsInOrgNotProject) {
            mismatches.add(new AssetMismatch(
                    AssetType.DOMAIN, domain, MismatchType.IN_ORG_NOT_IN_PROJECT,
                    "Expected by foundation but not declared in project"));
        }

        for (String org : orgsInProjectNotOrg) {
            mismatches.add(new AssetMismatch(
                    AssetType.GITHUB_ORG, org, MismatchType.IN_PROJECT_NOT_IN_ORG,
                    "Declared in project but not registered with foundation"));
        }

        for (String org : orgsInOrgNotProject) {
            mismatches.add(new AssetMismatch(
                    AssetType.GITHUB_ORG, org, MismatchType.IN_ORG_NOT_IN_PROJECT,
                    "Expected by foundation but not declared in project"));
        }

        return new ProjectAssetReconciliation(
                projectName,
                repoFullName,
                projectState,
                new ArrayList<>(matchedDomains),
                new ArrayList<>(matchedOrgs),
                mismatches);
    }

    /**
     * Get the set of assets expected for a specific project from the org config
     */
    private Set<String> getExpectedAssetsForProject(
            String projectName,
            String repoFullName,
            Map<String, Set<String>> assetToProjects) {

        Set<String> expected = new HashSet<>();
        for (var entry : assetToProjects.entrySet()) {
            String asset = entry.getKey();
            Set<String> projects = entry.getValue();
            // Check if this project is in the set (by name or by repo)
            if (projects.contains(projectName) || projects.contains(repoFullName)) {
                expected.add(asset);
            }
        }
        return expected;
    }

    /**
     * Extract project name from repository full name
     * (e.g., "commonhaus/project-foo" -> "foo")
     */
    private String extractProjectName(String repoFullName) {
        String repoName = repoFullName.substring(repoFullName.indexOf('/') + 1);
        if (repoName.startsWith("project-")) {
            return repoName.substring(8);
        }
        return repoName;
    }

    /**
     * Format tech contact information for display in email
     */
    private String formatTechContact(ProjectConfigState projectState) {
        var domainMgmt = projectState.projectConfig().domainManagement();
        if (domainMgmt == null || domainMgmt.getTechContact().isEmpty()) {
            return "Not specified";
        }

        var contact = domainMgmt.getTechContact().get();
        List<String> lines = new ArrayList<>();

        // Name
        lines.add(String.format("%s %s", contact.firstName(), contact.lastName()));

        // Organization and job title
        if (contact.organization().isPresent()) {
            lines.add("  Organization: " + contact.organization().get());
        }
        if (contact.jobTitle().isPresent()) {
            lines.add("  Job Title: " + contact.jobTitle().get());
        }

        // Email
        lines.add("  Email: " + contact.emailAddress());

        // Phone
        if (contact.phone() != null && !contact.phone().isBlank()) {
            String phone = "  Phone: " + contact.phone();
            if (contact.phoneExt().isPresent()) {
                phone += " ext. " + contact.phoneExt().get();
            }
            lines.add(phone);
        }

        // Address fields (each on its own line if present)
        if (contact.address1() != null && !contact.address1().isBlank()) {
            lines.add("  Address 1: " + contact.address1());
        }
        if (contact.address2().isPresent()) {
            lines.add("  Address 2: " + contact.address2().get());
        }
        if (contact.city() != null && !contact.city().isBlank()) {
            lines.add("  City: " + contact.city());
        }
        if (contact.stateProvince() != null && !contact.stateProvince().isBlank()) {
            lines.add("  State/Province: " + contact.stateProvince());
        }
        if (contact.postalCode() != null && !contact.postalCode().isBlank()) {
            lines.add("  Postal Code: " + contact.postalCode());
        }
        if (contact.country() != null && !contact.country().isBlank()) {
            lines.add("  Country: " + contact.country());
        }

        // Fax (if present)
        if (contact.fax().isPresent()) {
            lines.add("  Fax: " + contact.fax().get());
        }

        return String.join("\n", lines);
    }

    /**
     * Send annual asset report email to a project
     *
     * @param recon reconciliation results for the project
     * @param dryRun if true, log what would be sent instead of actually sending
     */
    private void sendAnnualReport(ProjectAssetReconciliation recon, boolean dryRun) {
        var projectState = recon.projectState();

        // Get audit email addresses
        String[] auditAddresses = projectState.projectConfig()
                .emailNotifications().audit();

        if (auditAddresses == null || auditAddresses.length == 0) {
            auditAddresses = projectState.projectConfig().emailNotifications().errors();
        }

        // Get tech contact info for display - format all available fields
        String techContactInfo = formatTechContact(projectState);

        // Sort lists for consistent output
        recon.matchedDomains().sort(String::compareTo);
        recon.matchedGithubOrgs().sort(String::compareTo);
        recon.mismatches().sort((a, b) -> {
            int typeCompare = a.assetType().compareTo(b.assetType());
            return typeCompare != 0 ? typeCompare : a.assetName().compareTo(b.assetName());
        });

        // Render template
        String body = Templates.annualReport(
                recon.projectName(),
                recon.repoFullName(),
                techContactInfo,
                recon.matchedDomains(),
                recon.matchedGithubOrgs(),
                recon.mismatches(),
                mgrBotConfig.home().repositoryFullName()).render();

        String subject = "Commonhaus annual check-in";

        if (dryRun) {
            Log.infof("[%s] DRY RUN: Would send annual report to %s", ME, recon.repoFullName());
            Log.infof("[%s]   Subject: %s", ME, subject);
            Log.infof("[%s]   Recipients: %s", ME, String.join(", ", auditAddresses));
            Log.infof("[%s]   Body preview:\n%s", ME, body);
        } else {
            Log.infof("[%s] Sending annual report to %s", ME, recon.repoFullName());
            ctx.sendEmail(ME, subject, body, auditAddresses);
        }

        // CC: foundation audit/dry-run address
        ctx.sendEmail(ME, subject, body, dryRun
                ? latestOrgConfig.getConfig().emailNotifications().dryRun()
                : latestOrgConfig.getConfig().emailNotifications().audit());
    }

    /**
     * Internal record for holding reconciliation results per project
     */
    record ProjectAssetReconciliation(
            String projectName,
            String repoFullName,
            ProjectConfigState projectState,
            List<String> matchedDomains,
            List<String> matchedGithubOrgs,
            List<AssetMismatch> mismatches) {
    }

    /**
     * Represents a single asset mismatch
     */
    record AssetMismatch(
            AssetType assetType,
            String assetName,
            MismatchType mismatchType,
            String description) {
    }

    /**
     * Type of asset (domain or GitHub organization)
     */
    enum AssetType {
        DOMAIN,
        GITHUB_ORG
    }

    /**
     * Type of mismatch between project and org config
     */
    enum MismatchType {
        IN_PROJECT_NOT_IN_ORG, // Project declares it but org doesn't expect it
        IN_ORG_NOT_IN_PROJECT // Org expects it but project doesn't declare it
    }

    @Override
    protected String me() {
        return ME;
    }
}
