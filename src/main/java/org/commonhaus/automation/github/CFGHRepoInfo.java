package org.commonhaus.automation.github;

import java.util.List;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHRepository;

import io.quarkus.logging.Log;

/**
 * Cache information about a repository with the GHApp installed
 */
public class CFGHRepoInfo {

    GHRepository ghRepository;
    long ghiId;

    List<GHLabel> labels;

    public CFGHRepoInfo(CFGHQueryContext queryContext) {
        this.ghRepository = queryContext.getGhRepository();
        this.ghiId = queryContext.getGhiId();
        this.labels = queryContext.getLabels();
    }

    public void logRepositoryInformation() {
        Log.infof("GitHub Repository: %s (%s)", 
            ghRepository.getFullName(), ghiId);

        for (GHLabel l : labels) {
            Log.infof("Label: %s %s", l.getId(), l.getName());
        }
   }

   public List<Discussion> queryDiscussions(CFGHQueryContext queryContext, boolean isOpen) {
       if (queryContext.hasErrors()) {
           return List.of();
       }
       return Discussion.queryDiscussions(queryContext, isOpen);
   }

   public List<DiscussionCategory> queryDiscussionCategories(CFGHQueryContext queryContext) {
    if (queryContext.hasErrors()) {
        return List.of();
    }
    return DiscussionCategory.queryDiscussionCategories(queryContext);
}
}
