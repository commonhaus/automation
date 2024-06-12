package org.commonhaus.automation.admin.github;

import java.util.List;
import java.util.Objects;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;

import org.commonhaus.automation.admin.AdminDataCache;
import org.commonhaus.automation.admin.api.ApplicationData;
import org.commonhaus.automation.admin.api.MemberApplicationProcess;
import org.commonhaus.automation.admin.config.UserManagementConfig.AttestationConfig;
import org.commonhaus.automation.github.context.ActionType;
import org.commonhaus.automation.github.context.DataCommonComment;
import org.commonhaus.automation.github.context.DataCommonItem;
import org.commonhaus.automation.github.context.DataLabel;
import org.commonhaus.automation.github.context.JsonAttribute;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkiverse.githubapp.event.Issue;
import io.quarkiverse.githubapp.event.IssueComment;
import io.quarkiverse.githubapp.event.Label;
import io.quarkiverse.githubapp.event.Membership;
import io.quarkiverse.githubapp.event.Push;
import io.quarkus.logging.Log;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

@ApplicationScoped
public class AdminGitHubEvents {

    @Inject
    AppContextService ctx;

    @Inject
    MemberApplicationProcess applicationProcess;

    public void updateAttestationList(GitHub github, DynamicGraphQLClient graphQLClient,
            @Push GHEventPayload.Push pushEvent) {
        AttestationConfig cfg = ctx.attestationConfig();
        if (cfg == null) {
            return;
        }

        GHRepository repo = pushEvent.getRepository();
        String repoFullName = repo.getFullName();
        long installationId = pushEvent.getInstallation().getId();

        if (Objects.equals(repoFullName, cfg.repo())
                && pushEvent.getRef().endsWith("/main")
                && ctx.commitsContain(pushEvent, cfg.path())) {
            Log.debugf("updateAttestationList (push): %s", repoFullName);

            ScopedQueryContext qc = ctx.refreshScopedQueryContext(installationId, repo);
            if (qc == null) {
                ctx.logAndSendEmail("" + installationId,
                        "updateAttestationList: Unable to get query context for " + repoFullName, null, null);
                return;
            }
            qc.addExisting(github).addExisting(graphQLClient);
            ctx.updateValidAttestations(qc);
        }
    }

    public void updateMembership(GitHub github, DynamicGraphQLClient graphQLClient,
            @Membership GHEventPayload.Membership membershipEvent) {

        AdminDataCache.KNOWN_USER.invalidate(membershipEvent.getMember().getLogin());
        GHRepository repo = membershipEvent.getRepository();
        GHOrganization org = membershipEvent.getOrganization();

        long installationId = membershipEvent.getInstallation().getId();
        String scope = repo == null ? org.getLogin() : repo.getFullName();

        ScopedQueryContext qc = ctx.refreshScopedQueryContext(installationId, org, repo);
        if (qc == null) {
            ctx.logAndSendEmail("" + installationId,
                    "updateMembership: Unable to get query context for " + scope, null, null);
            return;
        }
        qc.addExisting(github).addExisting(graphQLClient);
        qc.updateTeamList(membershipEvent.getOrganization(), membershipEvent.getTeam());
    }

    /**
     * Called when an issue is labeled
     *
     * @param event
     * @param github
     * @param graphQLClient
     * @param issueCommentEvent
     */
    public void applicationIssueLabelAdded(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @Issue.Labeled GHEventPayload.Issue issueEvent) {

        String repoFullName = issueEvent.getRepository().getFullName();
        ActionType actionType = ActionType.fromString(event.getAction());
        JsonObject payload = JsonAttribute.unpack(event.getPayload());
        DataCommonItem issue = JsonAttribute.issue.commonItemFrom(payload);
        DataLabel label = JsonAttribute.label.labelFrom(payload);

        // ignore if it isn't an issue in the datastore repository
        if (!repoFullName.equals(ctx.getDataStore())
                || !ApplicationData.isMemberApplicationEvent(issue, label)) {
            return;
        }

        ScopedQueryContext qc = ctx.refreshScopedQueryContext(
                issueEvent.getInstallation().getId(),
                issueEvent.getRepository())
                .addExisting(graphQLClient)
                .addExisting(github);

        Log.debugf("[%s] updateApplication #%s - %s", qc.getLogId(),
                issue.number, actionType);

        try {
            applicationProcess.handleApplicationLabelAdded(qc, issueEvent.getIssue(), issue, label);
        } catch (Exception e) {
            ctx.logAndSendEmail(qc.getLogId(), "Error with issue label event", e, null);
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
     * @param issueCommentEvent
     */
    public void updateApplicationComments(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @IssueComment GHEventPayload.IssueComment issueCommentEvent) {
        String repoFullName = issueCommentEvent.getRepository().getFullName();
        if (!repoFullName.equals(ctx.getDataStore())) {
            return;
        }
        ScopedQueryContext qc = ctx.refreshScopedQueryContext(
                issueCommentEvent.getInstallation().getId(),
                issueCommentEvent.getRepository())
                .addExisting(github)
                .addExisting(graphQLClient);

        GHIssue issue = issueCommentEvent.getIssue();
        String nodeId = issue.getNodeId();
        List<DataCommonComment> comments = qc.getComments(nodeId, x -> true);

        Log.debugf("[%s] updateApplicationComments: %s", qc.getLogId(),
                issue.getNumber(), comments);
    }

    /**
     * Called when there is a label change (added or removed from the repository) event.
     *
     * @param event GitHubEvent (raw payload)
     * @param github GitHub API (connection instance)
     * @param graphQLClient GraphQL client
     * @param labelPayload GitHub API parsed payload
     */
    void onRepositoryLabelChange(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @Label GHEventPayload.Label labelPayload) {
        String repoFullName = labelPayload.getRepository().getFullName();
        if (!repoFullName.equals(ctx.getDataStore())) {
            return;
        }

        ScopedQueryContext qc = ctx.refreshScopedQueryContext(
                labelPayload.getInstallation().getId(),
                labelPayload.getRepository())
                .addExisting(github)
                .addExisting(graphQLClient);

        DataLabel label = new DataLabel(labelPayload.getLabel());
        String cacheId = labelPayload.getRepository().getNodeId();
        ActionType actionType = ActionType.fromString(event.getAction());

        Log.debugf("[%s] LabelChanges: repository %s changed label %s", qc.getLogId(), cacheId, label);
        qc.modifyLabels(cacheId, label, actionType);
    }
}
