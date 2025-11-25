package org.commonhaus.automation.hm.config;

import java.util.Collection;

import org.commonhaus.automation.hm.ProjectManager.ProjectConfigState;

public interface LatestProjectConfig {

    ProjectConfigState getProjectConfigState(String repoFullName);

    ProjectConfigState getProjectStateByName(String project);

    Collection<ProjectConfigState> getAllProjects();

    /**
     * Callback for notifications when project config is updated
     */
    void notifyOnUpdate(String id, ProjectConfigListener listener);

    public interface ProjectConfigListener {
        /**
         * Convert a project/repository name to a task group
         */
        String getTaskGroup(String repoFullName);

        /**
         * Called when a project config is updated
         *
         * @param taskGroup The task group associated with the project
         * @param ProjectConfigState The updated project configuration state
         */
        void onProjectConfigUpdate(String taskGroup, ProjectConfigState projectConfig);
    }
}
