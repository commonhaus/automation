package org.commonhaus.automation.hm.config;

import static org.commonhaus.automation.github.context.GitHubQueryContext.toFullName;

public interface LatestOrgConfig {
    OrganizationConfig getConfig();

    void notifyOnUpdate(String id, Runnable callback);

    /**
     * Convert project name to repository full name.
     * Returns the constructed repository full name based on org config.
     * A ProjectConfigState is not required to exist - projects may not have
     * their own config files yet.
     */
    default String projectNameToRepoFullName(ManagerBotConfig botConfig, String projectName) {
        var assets = getConfig().projects().assetsForProject(projectName);
        var repoName = assets.projectRepository() == null
                ? "project-" + projectName
                : assets.projectRepository();
        var repoFullName = toFullName(botConfig.home().organization(), repoName);
        return repoFullName;
    }

    /**
     * Extract display name from repository full name.
     * Extracts the repository name from the full name (e.g., "project-easymock" from
     * "org/project-easymock"), then removes the "project-" prefix if present.
     * This handles cases where multiple projects share the same projectRepository.
     *
     * @param repoFullName The repository full name (e.g., "org/project-easymock")
     * @return The display name to use in emails and UI, or the repoFullName if it cannot be parsed
     */
    default String getProjectDisplayNameFromRepo(String repoFullName) {
        if (repoFullName == null || repoFullName.isEmpty()) {
            return "unknown";
        }

        // Extract repository name from full name (after the last '/')
        int lastSlash = repoFullName.lastIndexOf('/');
        String repoName = lastSlash >= 0 ? repoFullName.substring(lastSlash + 1) : repoFullName;

        // Remove "project-" prefix if present
        if (repoName.startsWith("project-")) {
            return repoName.substring("project-".length());
        }

        return repoName;
    }
}
