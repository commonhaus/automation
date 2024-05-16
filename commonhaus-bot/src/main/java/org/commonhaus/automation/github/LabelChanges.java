package org.commonhaus.automation.github;

import jakarta.inject.Inject;

import org.commonhaus.automation.github.context.DataDiscussion;
import org.commonhaus.automation.github.context.DataLabel;
import org.commonhaus.automation.github.context.EventData;
import org.commonhaus.automation.github.context.EventPayload;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkiverse.githubapp.event.Discussion;
import io.quarkiverse.githubapp.event.Issue;
import io.quarkiverse.githubapp.event.Label;
import io.quarkiverse.githubapp.event.PullRequest;
import io.quarkus.logging.Log;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

/**
 * Discussion webhook events do not include labels
 * Track label changes to keep a cache
 */
public class LabelChanges {

    @Inject
    AppContextService queryHelper;

    /**
     * Called when there is a discussion labeled/unlabeled event.
     *
     * @param event GitHubEvent (raw payload)
     * @param github GitHub API (connection instance)
     * @param graphQLClient GraphQL client
     * @param discussionPayload GitHub API parsed payload
     */
    void onDiscussionLabelChangeEvent(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @Discussion.Labeled @Discussion.Unlabeled GHEventPayload.Discussion discussionPayload) {

        final EventData initialData = new EventData(event, discussionPayload);
        EventQueryContext qc = queryHelper.newQueryContext(initialData, github, graphQLClient);

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
        qc.modifyLabels(discussion.id, label, initialData.getActionType());
    }

    /**
     * Called when there is an issue labeled/unlabeled event.
     *
     * @param event GitHubEvent (raw payload)
     * @param github GitHub API (connection instance)
     * @param graphQLClient GraphQL client
     * @param parsedPayload GitHub API parsed payload
     */
    void onPullRequestLabelChangeEvent(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @PullRequest.Labeled @PullRequest.Unlabeled GHEventPayload.PullRequest parsedPayload) {

        final EventData initialData = new EventData(event, parsedPayload);
        EventQueryContext qc = queryHelper.newQueryContext(initialData, github, graphQLClient);

        GHLabel label = parsedPayload.getLabel();
        GHIssue issue = parsedPayload.getPullRequest();

        Log.debugf("[%s] LabelChanges: PR changed label %s", initialData.getLogId(), label);
        qc.modifyLabels(issue.getNodeId(), new DataLabel(label), initialData.getActionType());
    }

    /**
     * Called when there is an issue labeled/unlabeled event.
     *
     * @param event GitHubEvent (raw payload)
     * @param github GitHub API (connection instance)
     * @param graphQLClient GraphQL client
     * @param parsedPayload GitHub API parsed payload
     */
    void onIssueLabelChangeEvent(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @Issue.Labeled @Issue.Unlabeled GHEventPayload.Issue parsedPayload) {

        final EventData initialData = new EventData(event, parsedPayload);
        EventQueryContext qc = queryHelper.newQueryContext(initialData, github, graphQLClient);

        GHLabel label = parsedPayload.getLabel();
        GHIssue issue = parsedPayload.getIssue();

        Log.debugf("[%s] LabelChanges: issue changed label %s", initialData.getLogId(), label);
        qc.modifyLabels(issue.getNodeId(), new DataLabel(label), initialData.getActionType());
    }

    /**
     * Called when there is event.
     *
     * @param event GitHubEvent (raw payload)
     * @param github GitHub API (connection instance)
     * @param graphQLClient GraphQL client
     * @param labelPayload GitHub API parsed payload
     */
    void onRepositoryLabelChange(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @Label GHEventPayload.Label labelPayload) {

        if (labelPayload.getRepository() == null) {
            return;
        }

        final EventData initialData = new EventData(event, labelPayload);
        EventQueryContext qc = queryHelper.newQueryContext(initialData, github, graphQLClient);

        DataLabel label = new DataLabel(labelPayload.getLabel());
        String cacheId = labelPayload.getRepository().getNodeId();

        Log.debugf("[%s] LabelChanges: repository %s changed label %s", initialData.getLogId(), cacheId, label);
        qc.modifyLabels(cacheId, label, initialData.getActionType());
    }
}
