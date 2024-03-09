package org.commonhaus.automation.github;

import jakarta.inject.Inject;

import org.commonhaus.automation.github.model.DataDiscussion;
import org.commonhaus.automation.github.model.DataLabel;
import org.commonhaus.automation.github.model.EventPayload;
import org.commonhaus.automation.github.model.EventQueryContext;
import org.commonhaus.automation.github.model.QueryHelper;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkiverse.githubapp.event.Discussion;
import io.quarkiverse.githubapp.event.Label;
import io.quarkus.logging.Log;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

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
     */
    void onDiscussionLabelChangeEvent(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @Discussion.Labeled @Discussion.Unlabeled GHEventPayload.Discussion discussionPayload) {

        final EventData initialData = new EventData(event, discussionPayload);
        EventQueryContext queryContext = queryHelper.newQueryContext(initialData, github, graphQLClient);

        EventPayload.DiscussionPayload payload = initialData.getEventPayload();
        if (payload == null) {
            Log.errorf("[%s] LabelChanges: no event payload, %s", initialData.getLogId(), event.getPayload());
            return;
        }
        DataDiscussion discussion = payload.discussion;
        DataLabel label = payload.label;
        if (discussion == null || label == null) {
            Log.errorf("[%s] LabelChanges: missing discussion or label, %s", initialData.getLogId(), event.getPayload());
            return;
        }

        Log.debugf("[%s] LabelChanges: discussion changed label %s", initialData.getLogId(), label);
        queryContext.modifyLabels(discussion.id, label, initialData.getActionType());
    }

    /**
     * Called when there is event.
     *
     * @param event GitHubEvent (raw payload)
     * @param github GitHub API (connection instance)
     */
    void onRepositoryLabelChange(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @Label GHEventPayload.Label labelPayload) {

        if (labelPayload.getRepository() == null) {
            return;
        }

        final EventData initialData = new EventData(event, labelPayload);
        EventQueryContext queryContext = queryHelper.newQueryContext(initialData, github, graphQLClient);

        DataLabel label = new DataLabel(labelPayload.getLabel());
        String cacheId = labelPayload.getRepository().getNodeId();

        Log.debugf("[%s] LabelChanges: repository %s changed label %s", initialData.getLogId(), cacheId, label);
        queryContext.modifyLabels(cacheId, label, initialData.getActionType());
    }
}
