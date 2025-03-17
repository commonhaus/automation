package org.commonhaus.automation.hm.config;

import java.util.List;
import java.util.Set;

import jakarta.annotation.Nonnull;

import org.commonhaus.automation.config.EmailNotification;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Configuration file for the Haus Manager.
 * Located in a repository at .github/cf-haus-manager.yml
 */
public class ProjectConfig {
    public static final String NAME = "cf-haus-manager.yml";
    public static final String PATH = ".github/" + NAME;

    protected Boolean enabled;
    protected Boolean dryRun;

    protected String gitHubResources;
    protected TeamAccess teamAccess;
    protected EmailNotification emailNotifications;

    @JsonIgnore
    private Set<String> allResources;

    /**
     * Return list of teams that should have membership
     * syncnronized from a source team.
     */
    public TeamAccess teamAccess() {
        return teamAccess;
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

    @Override
    public String toString() {
        return "ProjectConfigFile{dryRun=%s, enabled='%s', %s, %s}"
                .formatted(dryRun(), enabled(), emailNotifications(), teamAccess());
    }

    /**
     * Define the source team (from another repository) that should be
     * synchronized with outside collaborators of the repo
     * containing this configuration file.
     *
     * @param source Source team to synchronize from (organization/teamName)
     * @param ignoreUsers List of users to ignore when syncing
     */
    public record TeamAccess(
            String source,
            List<String> ignoreUsers) {

        @Override
        public List<String> ignoreUsers() {
            return ignoreUsers != null ? ignoreUsers : List.of();
        }

        @Override
        public String toString() {
            return "TeamAccess{source=%s, ignoreUsers='%s'}"
                    .formatted(source, ignoreUsers());
        }
    }
}
