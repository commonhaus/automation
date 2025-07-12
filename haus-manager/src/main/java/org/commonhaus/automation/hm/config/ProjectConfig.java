package org.commonhaus.automation.hm.config;

import java.util.List;
import java.util.Map;

import jakarta.annotation.Nonnull;

import org.commonhaus.automation.config.EmailNotification;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Configuration file for the Haus Manager.
 * Located in a repository at .github/cf-haus-manager.yml
 */
@RegisterForReflection
public class ProjectConfig {
    public static final String NAME = "cf-haus-manager.yml";
    public static final String PATH = ".github/" + NAME;

    protected Boolean enabled;
    protected Boolean dryRun;

    protected CollaboratorSync collaboratorSync;
    protected List<GroupMapping> teamMembership;
    protected EmailNotification emailNotifications;
    protected ProjectHealth projectHealth;

    /**
     * Return list of teams that should have membership
     * synchronized from a source team.
     */
    public CollaboratorSync collaboratorSync() {
        return collaboratorSync;
    }

    public List<GroupMapping> teamMembership() {
        return teamMembership == null ? List.of() : teamMembership;
    }

    /** If present and true, do not modify any resources */
    public boolean dryRun() {
        return dryRun != null && dryRun;
    }

    /** If absent or true, this configuration is enabled */
    public boolean enabled() {
        return enabled == null || enabled;
    }

    /** Email notifications configuration */
    @Nonnull
    public EmailNotification emailNotifications() {
        return emailNotifications == null
                ? EmailNotification.UNDEFINED
                : emailNotifications;
    }

    /** Project health configuration */
    public ProjectHealth projectHealth() {
        return projectHealth;
    }

    @Override
    public String toString() {
        return "ProjectConfigFile{dryRun=%s, enabled='%s', %s, %s}"
                .formatted(dryRun(), enabled(), emailNotifications(), collaboratorSync());
    }

    /**
     * Define the source team (from another repository) that should be
     * synchronized with outside collaborators of the repo
     * containing this configuration file.
     *
     * @param sourceTeam Source team to synchronize from (organization/teamName)
     * @param ignoreUsers List of users to ignore when syncing
     */
    public record CollaboratorSync(
            String role,
            String sourceTeam,
            List<String> includeUsers,
            List<String> ignoreUsers) {

        @Override
        public List<String> includeUsers() {
            return includeUsers != null ? includeUsers : List.of();
        }

        @Override
        public List<String> ignoreUsers() {
            return ignoreUsers != null ? ignoreUsers : List.of();
        }

        @Override
        public String role() {
            return role != null ? role : "triage";
        }

        @Override
        public String toString() {
            return "TeamAccess{sourceTeam=%s, role=%s, logins=%s, ignoreUsers=%s}"
                    .formatted(sourceTeam(), role(), includeUsers(), ignoreUsers());
        }
    }

    /**
     * Project health configuration for foundation oversight
     *
     * @param maturity Project maturity level (early, mature, experimental,
     *        deprecated, etc.)
     * @param organizationRepositories Map of organization to repository configuration.
     */
    public record ProjectHealth(
            String maturity,
            Map<String, RepositoryConfig> organizationRepositories) {

        public RepositoryConfig repositoryConfig(String orgName) {
            if (organizationRepositories == null || organizationRepositories.isEmpty()) {
                return RepositoryConfig.EMPTY;
            }
            RepositoryConfig repositories = organizationRepositories.get(orgName);
            return repositories != null ? repositories : RepositoryConfig.EMPTY;
        }
    }

    /**
     * Repository configuration for health tracking
     *
     * @param excludes List of repository names to exclude from health tracking
     * @param releases Map of repository names to expected release frequency
     */
    public record RepositoryConfig(
            List<String> excludes,
            Map<String, String> releases) {

        static final RepositoryConfig EMPTY = new RepositoryConfig(List.of(), Map.of());

        @Override
        public List<String> excludes() {
            return excludes != null ? excludes : List.of();
        }

        @Override
        public Map<String, String> releases() {
            return releases != null ? releases : Map.of();
        }

        /**
         * Check if a repository should be excluded from health tracking
         */
        public boolean isExcluded(String repositoryName) {
            return excludes().contains(repositoryName);
        }

        /**
         * Get expected release frequency for a repository
         */
        public String getReleaseFrequency(String repositoryName) {
            return releases().get(repositoryName);
        }
    }
}
