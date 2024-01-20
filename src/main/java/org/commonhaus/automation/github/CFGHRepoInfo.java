package org.commonhaus.automation.github;

import java.io.IOException;
import java.util.List;

import org.commonhaus.automation.github.CFGHQueryHelper.RepoQuery;
import org.commonhaus.automation.github.model.Discussion;
import org.commonhaus.automation.github.model.DiscussionCategory;
import org.commonhaus.automation.github.model.Label;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHRepository;

import io.quarkus.logging.Log;

public class CFGHRepoInfo {
    final String owner;
    final String name;
    final String fullName;
    final long ghiId;

    CFGHRepoInfo(GHRepository ghRepository, long ghiId) {
        // Note that GHRepository is bound to a root (GitHub instance) that
        // will only be valid for a short time. Don't hold onto that resource
        this.owner = ghRepository.getOwnerName();
        this.name = ghRepository.getName();
        this.fullName = ghRepository.getFullName();
        this.ghiId = ghiId;
    }

    public String getOwner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    public String getFullName() {
        return fullName;
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

    public List<Label> getRepositoryLabels(RepoQuery queryContext) {
        if (queryContext.hasErrors()) {
            return List.of();
        }
        // TODO: cache data (?)
        try {
            GHRepository ghRepository = queryContext.getGHRepository();
            List<GHLabel> ghLabels = ghRepository.listLabels().toList();
            return Label.fromGHLabels(ghLabels);
        } catch (IOException e) {
            Log.errorf(e, "Error executing GraphQL query for repository %s: %s",
                    getFullName(), e.toString());
            queryContext.addException(e);
        }
        return List.of();
    }
}