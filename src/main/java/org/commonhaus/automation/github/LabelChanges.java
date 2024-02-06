package org.commonhaus.automation.github;

import jakarta.inject.Inject;

import org.commonhaus.automation.github.QueryHelper.QueryContext;
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
            Log.errorf("LabelChanges (%s): no event payload, %s", event.getEventAction(), event.getPayload());
            return;
        }
        DataDiscussion discussion = payload.discussion;
        DataLabel label = payload.label;
        if (discussion == null || label == null) {
            Log.errorf("LabelChanges (%s): missing discussion or label, %s", event.getEventAction(), event.getPayload());
            return;
        }

        Log.debugf("LabelChanges (%s): discussion %s changed label %s", event.getEventAction(), discussion.id, label);
        queryContext.modifyLabels(discussion.id, label, initialData.getActionType());
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

        Log.debugf("LabelChanges (%s): repository %s changed label %s", event.getEventAction(), cacheId, label);
        queryContext.modifyLabels(cacheId, label, initialData.getActionType());
    }
}
