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
}
