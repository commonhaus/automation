package org.commonhaus.automation.github;

import java.io.IOException;
import java.util.List;

import org.commonhaus.automation.github.CFGHQueryHelper.RepoQuery;
import org.commonhaus.automation.github.model.Discussion;
import org.commonhaus.automation.github.model.DiscussionCategory;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHProject;
import org.kohsuke.github.GHRepository;

import io.quarkus.logging.Log;

public class CFGHRepoInfo {
    final GHRepository ghRepository;
    final long ghiId;

    CFGHRepoInfo(GHRepository ghRepository, long ghiId) {
        this.ghRepository = ghRepository;
        this.ghiId = ghiId;
    }

    public String getFullName() {
        return ghRepository.getFullName();
    }

    public List<Discussion> queryDiscussions(RepoQuery queryContext, boolean isOpen) {
        if (queryContext.hasErrors()) {
            return List.of();
        }
        // TODO: cache response (?)
        return Discussion.queryDiscussions(queryContext, isOpen);
    }

    public List<DiscussionCategory> queryDiscussionCategories(RepoQuery queryContext) {
        if (queryContext.hasErrors()) {
            return List.of();
        }
        // TODO: cache response (?)
        return DiscussionCategory.queryDiscussionCategories(queryContext);
    }

    public List<GHLabel> getLabels(RepoQuery queryContext) {
        if (queryContext.hasErrors()) {
            return List.of();
        }
        // TODO: cache response (?)
        try {
            return ghRepository.listLabels().toList();
        } catch (IOException e) {
            Log.errorf(e, "Error executing GraphQL query for repository %s: %s",
                    ghRepository.getFullName(), e.toString());
            queryContext.addException(e);
        }
        return List.of();
    }

    public List<GHProject> getProjects(RepoQuery queryContext) {
        if (queryContext.hasErrors()) {
            return List.of();
        }
        // TODO: cache response (?)
        try {
            return ghRepository.listProjects().toList();
        } catch (IOException e) {
            Log.errorf(e, "Error listing projects for repository %s: %s", getFullName(), e);
            queryContext.addException(e);
        }
        return List.of();
    }
}