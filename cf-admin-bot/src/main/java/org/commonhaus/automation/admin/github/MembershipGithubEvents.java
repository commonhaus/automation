package org.commonhaus.automation.admin.github;

import jakarta.inject.Inject;
import jakarta.json.JsonObject;

import org.commonhaus.automation.admin.api.MemberApplicationProcess;
import org.commonhaus.automation.admin.api.MembershipApplicationData;
import org.commonhaus.automation.github.context.ActionType;
import org.commonhaus.automation.github.context.DataCommonComment;
import org.commonhaus.automation.github.context.DataCommonItem;
import org.commonhaus.automation.github.context.DataLabel;
import org.commonhaus.automation.github.context.JsonAttribute;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkiverse.githubapp.event.Issue;
import io.quarkiverse.githubapp.event.IssueComment;
import io.quarkus.logging.Log;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public class MembershipGithubEvents {

    @Inject
    AppContextService ctx;

    @Inject
    MemberApplicationProcess applicationProcess;

    /**
     * Called when an issue is labeled
     *
     * @param event
     * @param github
     * @param graphQLClient
     * @param eventPayload
     */
    public void applicationIssueLabelAdded(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @Issue.Labeled GHEventPayload.Issue eventPayload) {
        long installationId = eventPayload.getInstallation().getId();
        String repoFullName = eventPayload.getRepository().getFullName();
        Log.debugf("[%s] applicationIssueLabelAdded: %s",
                installationId, repoFullName);

        ActionType actionType = ActionType.fromString(event.getAction());
        JsonObject data = JsonAttribute.unpack(event.getPayload());
        DataCommonItem issue = JsonAttribute.issue.commonItemFrom(data);
        DataLabel label = JsonAttribute.label.labelFrom(data);

        // ignore if it isn't an issue in the datastore repository
        if (!repoFullName.equals(ctx.getDataStore())
                || !MembershipApplicationData.isMemberApplicationEvent(issue, label)) {
            return;
        }

        ScopedQueryContext qc = ctx.refreshScopedQueryContext(
                installationId,
                eventPayload.getRepository())
                .addExisting(graphQLClient)
                .addExisting(github);

        Log.debugf("[%s] applicationIssueLabelAdded #%s - %s", qc.getLogId(),
                issue.number, actionType);

        try {
            qc.getLabels(qc.getRepositoryId()); // pre-fetch
            applicationProcess.handleApplicationLabelAdded(qc, eventPayload.getIssue(), issue, label);
        } catch (Throwable e) {
            qc.logAndSendEmail("Error with issue label event", e);
        } finally {
            qc.clearErrors();
        }
    }

    /**
     * Called when there is a comment event on an issue.
     *
     * @param event
     * @param github
     * @param graphQLClient
     * @param eventPayload
     */
    public void updateApplicationComments(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @IssueComment GHEventPayload.IssueComment eventPayload) {
        long installationId = eventPayload.getInstallation().getId();
        String repoFullName = eventPayload.getRepository().getFullName();
        Log.debugf("[%s] updateApplicationComments: %s",
                installationId, repoFullName);

        JsonObject data = JsonAttribute.unpack(event.getPayload());
        DataCommonItem issue = JsonAttribute.issue.commonItemFrom(data);

        // ignore if it isn't an issue in the datastore repository
        if (!repoFullName.equals(ctx.getDataStore())
                || !MembershipApplicationData.isMemberApplicationEvent(issue, null)) {
            return;
        }

        ScopedQueryContext qc = ctx.refreshScopedQueryContext(
                eventPayload.getInstallation().getId(),
                eventPayload.getRepository())
                .addExisting(github)
                .addExisting(graphQLClient);

        DataCommonComment comment = JsonAttribute.comment.commonCommentFrom(data);
        try {
            applicationProcess.handleApplicationComment(qc, issue, comment);
        } catch (Exception e) {
            qc.logAndSendEmail("Error with issue label event", e);
        } finally {
            qc.clearErrors();
        }
    }

}
