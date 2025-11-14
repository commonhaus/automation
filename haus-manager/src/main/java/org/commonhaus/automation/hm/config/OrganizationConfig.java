package org.commonhaus.automation.hm.config;

import java.util.List;

import org.commonhaus.automation.config.EmailNotification;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class OrganizationConfig {
    public static final String NAME = "cf-haus-organization.yml";
    public static final String PATH = ".github/" + NAME;

    protected EmailNotification emailNotifications;
    protected List<GroupMapping> teamMembership;
    protected DomainManagementConfig domainManagement;
    protected EnabledDryRunConfig githubOrgVerification;
    protected ProjectAssetList projects;
    protected SponsorsConfig sponsors;

    /**
     * @return the emailNotifications
     */
    public EmailNotification emailNotifications() {
        return emailNotifications == null
                ? EmailNotification.UNDEFINED
                : emailNotifications;
    }

    /**
     * @return the sponsors
     */
    public SponsorsConfig sponsors() {
        return sponsors;
    }

    /**
     * @return the teamMembership
     */
    public List<GroupMapping> teamMembership() {
        return teamMembership == null ? List.of() : teamMembership;
    }

    /**
     * @return the domain management configuration
     */
    public DomainManagementConfig domainManagement() {
        return domainManagement;
    }

    /**
     * @return the GitHub organization verification configuration
     */
    public EnabledDryRunConfig githubOrgVerification() {
        return githubOrgVerification;
    }

    /**
     * @return the projects configuration
     */
    public ProjectAssetList projects() {
        return projects;
    }

    @Override
    public String toString() {
        return "OrganizationConfig{emailNotifications=%s, sponsors=%s, teamMembership=%s, domainManagement=%s, githubOrgVerification=%s, projects=%s}"
                .formatted(emailNotifications, sponsors, teamMembership, domainManagement, githubOrgVerification, projects);
    }

    /**
     * Automation to verify/synchronize/discover sponsors
     * and other financial supporters of the organization.
     *
     * @param enabled Whether sponsor synchronization is enabled
     * @param dryRun If true, do not update the repository
     * @param sponsorable The sponsorable entity (org or user)
     * @param targetRepository Repository to update with sponsors (as outside contributors)
     * @param role Role to assign to the sponsors (default: 'triage')
     * @param ignoreUsers List of users to ignore (do not add or remove)
     */
    public record SponsorsConfig(
            Boolean enabled,
            Boolean dryRun,
            String sponsorable,
            String targetRepository,
            String role,
            List<String> ignoreUsers) {

        public boolean isEnabled() {
            return enabled == null || enabled;
        }

        public boolean isDryRun() {
            return dryRun != null && dryRun;
        }

        @Override
        public String role() {
            return role != null ? role : "triage";
        }

        @Override
        public List<String> ignoreUsers() {
            return ignoreUsers == null ? List.of() : ignoreUsers;
        }

        @Override
        public String toString() {
            return "SponsorsConfig{enabled=%s, dryRun=%s, targetRepository='%s', sponsorable=%s, ignoreUsers=%s}"
                    .formatted(isEnabled(), isDryRun(), targetRepository, sponsorable, ignoreUsers);
        }
    }

    /**
     * Default/fallback values for team configuration.
     *
     * @param field The field in the source file that contains the login (usually 'login')
     * @param preserveUsers List of users to preserve in the teams (add if missing)
     * @param ignoreUsers List of users to ignore in the teams (do nothing / skip)
     */
    public record OrgDefaults(
            String field,
            List<String> preserveUsers,
            List<String> ignoreUsers) {
        @Override
        public String field() {
            return field == null ? "login" : field;
        }

        @Override
        public List<String> preserveUsers() {
            return preserveUsers == null ? List.of() : preserveUsers;
        }

        @Override
        public List<String> ignoreUsers() {
            return ignoreUsers == null ? List.of() : ignoreUsers;
        }

        @Override
        public String toString() {
            return "OrgDefaults{field='%s', ignoreUsers=%s}"
                    .formatted(field, ignoreUsers);
        }
    }

    /**
     * Ensure that the team name is fully qualified with the organization name.
     * If the team name is already fully qualified, it is returned as is.
     * Otherwise, the organization _of the source file_ is prepended to the team name.
     *
     * @param teamName
     * @return
     */
    public static String toFullTeamName(String defaultOrg, String teamName) {
        if (teamName.contains("/")) {
            return teamName;
        }
        return "%s/%s".formatted(defaultOrg, teamName);
    }
}
