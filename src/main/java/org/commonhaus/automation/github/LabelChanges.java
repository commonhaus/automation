package org.commonhaus.automation.github;

import jakarta.inject.Inject;

import org.commonhaus.automation.github.QueryHelper.QueryContext;
import org.commonhaus.automation.github.model.ActionType;
import org.commonhaus.automation.github.model.DataDiscussion;
import org.commonhaus.automation.github.model.DataLabel;
import org.commonhaus.automation.github.model.EventPayload;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkiverse.githubapp.event.Discussion;
import io.quarkiverse.githubapp.event.Label;
import io.quarkus.logging.Log;

/**
 * Discussion webhook events do not include labels
 * Track label changes to keep a cache
 */
public class LabelChanges {

    @Inject
    QueryHelper queryHelper;

    /**
     * Called when there is a discussion labeled/unlabeled event.
     *
     * @param event GitHubEvent (raw payload)
     * @param github GitHub API (connection instance)
     * @param discussionPayload GitHub API parsed payload
     * @param repoConfigFile CFGH RepoConfig (if exists)
     */
    void onDiscussionLabelChangeEvent(GitHubEvent event, GitHub github,
            @Discussion.Labeled @Discussion.Unlabeled GHEventPayload.Discussion discussionPayload,
            @ConfigFile(RepositoryAppConfig.NAME) RepositoryAppConfig.File repoConfigFile) {

        final EventData initialData = new EventData(event, discussionPayload);
        QueryContext queryContext = queryHelper.newQueryContext(initialData, github);

        EventPayload.DiscussionPayload payload = initialData.getEventPayload();
        if (payload == null) {
            Log.errorf("DiscussionLabels.onLabelChangeEvent: no event payload, %s", event.getPayload());
            return;
        }
        DataDiscussion discussion = payload.discussion;
        DataLabel label = payload.label;
        if (discussion == null || label == null) {
            Log.errorf("DiscussionLabels.onLabelChangeEvent: missing discussion or label, %s", event.getPayload());
            return;
        }

        // Don't fetch labels first: only add/remove if it's an item/id we know about
        if (initialData.getActionType() == ActionType.labeled) {
            queryContext.addCachedLabel(discussion.id, label);
        } else {
            queryContext.removeCachedLabel(discussion.id, label);
        }
    }

    /**
     * Called when there is event.
     *
     * @param event GitHubEvent (raw payload)
     * @param github GitHub API (connection instance)
     * @param discussionPayload GitHub API parsed payload
     * @param repoConfigFile CFGH RepoConfig (if exists)
     */
    void onRepositoryLabelChange(GitHubEvent event, GitHub github,
            @Label GHEventPayload.Label labelPayload) {
        if (labelPayload.getRepository() == null) {
            return;
        }

        final EventData initialData = new EventData(event, labelPayload);
        QueryContext queryContext = queryHelper.newQueryContext(initialData, github);

        DataLabel label = new DataLabel(labelPayload.getLabel());
        String cacheId = labelPayload.getRepository().getNodeId();

        // Don't fetch labels first: only add/remove if it's an item/id we know about
        if (initialData.getActionType() == ActionType.created) {
            queryContext.addCachedLabel(cacheId, label);
        } else if (initialData.getActionType() == ActionType.edited) {
            queryContext.updateCachedLabel(cacheId, label);
        } else {
            queryContext.removeCachedLabel(cacheId, label);
        }
    }
}
