package org.commonhaus.automation.hm.config;

import java.util.List;

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
    protected TeamMapping[] teamSync;
    protected EmailNotification emailNotifications;

    /**
     * Return list of teams that should have membership
     * syncnronized from a source team.
     */
    public List<TeamMapping> teamSync() {
        return teamSync != null ? List.of(teamSync) : List.of();
    }

    /** If present and true, do not modify any resources */
    public boolean dryRun() {
        return dryRun != null && dryRun;
    }

    /** If absent or true, this configuration is enabled */
    public boolean enabled() {
        return enabled == null || enabled;
    }

    @Override
    public String toString() {
        return "ProjectConfigFile{dryRun=%s, enabled='%s', emailNotifications='%s', teamSync=%s}"
                .formatted(dryRun(), enabled(), emailNotifications, teamSync);
    }

    /**
     * This is a mapping of source teams to target teams and repositories.
     *
     * @param source Source team to synchronize from (organization/teamName)
     * @param ignoreUsers List of users to ignore when syncing
     * @param teams List of target teams to sync membership to (organization/teamName)
     * @param repositories List of repositories; All members from the source team will be added
     *        as outside collaborators to these repositories
     */
    public record TeamMapping(
            String source,
            List<String> ignoreUsers,
            List<String> teams,
            List<String> repositories) {

        @Override
        public List<String> ignoreUsers() {
            return ignoreUsers != null ? ignoreUsers : List.of();
        }

        @Override
        public List<String> teams() {
            return teams != null ? teams : List.of();
        }

        @Override
        public List<String> repositories() {
            return repositories != null ? repositories : List.of();
        }

        @Override
        public String toString() {
            return "TeamSyncConfig{source=%s, ignoreUsers='%s', teams='%s', repositories=%s}"
                    .formatted(source, ignoreUsers(), teams, repositories);
        }
    }
}
