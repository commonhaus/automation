package org.commonhaus.automation.github;

import java.util.List;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHProject;
import org.kohsuke.github.GHRepository;

import io.quarkus.logging.Log;

/**
 * Cache information about a repository with the GHApp installed
 */
public class RepositoryInfo {
    GHRepository ghRepository;
    long ghiId;

    List<GHLabel> labels;
    List<DiscussionCategory> categories;
    List<GHProject> projects;

    public RepositoryInfo(QueryContext queryContext) {
        this.ghRepository = queryContext.getGhRepository();
        this.ghiId = queryContext.getGhiId();
        this.labels = queryContext.getLabels();
        this.projects = queryContext.getProjects();
        this.categories = DiscussionCategory.listDiscussionCategories(queryContext);
    }

    public void logRepositoryInformation() {
        Log.infof("GitHub Repository: %s (%s)", 
            ghRepository.getFullName(), ghiId);

        for (GHProject p : projects) {
            Log.infof("Project: %s %s", p.getId(), p.getName());
        }
        for (GHLabel l : labels) {
            Log.infof("Label: %s %s", l.getId(), l.getName());
        }
        for (DiscussionCategory c : categories) {
            Log.infof("Discussion Category: %s %s", c.id.id, c.id.name);
        }
   }

   public List<Discussion> listDiscussions(QueryContext queryContext, boolean isOpen) {
       if (queryContext.hasErrors()) {
           return List.of();
       }
       return Discussion.listDiscussions(queryContext, isOpen);
   }
}
