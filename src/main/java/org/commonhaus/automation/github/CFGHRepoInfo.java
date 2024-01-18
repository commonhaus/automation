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

    public CFGHRepoInfo(GHRepository ghRepository, long id) {
        this.ghRepository = ghRepository;
        this.ghiId = id;
    }

    public void logRepositoryInformation() {
        Log.infof("GitHub Repository: %s (%s)",
                ghRepository.getFullName(), ghiId);
    }

    public List<Discussion> queryDiscussions(CFGHQueryHelper queryContext, boolean isOpen) {
        if (queryContext.hasErrors()) {
            return List.of();
        }
        return Discussion.queryDiscussions(queryContext, isOpen);
    }

    public List<DiscussionCategory> queryDiscussionCategories(CFGHQueryHelper queryContext) {
        if (queryContext.hasErrors()) {
            return List.of();
        }
        return DiscussionCategory.queryDiscussionCategories(queryContext);
    }
}
