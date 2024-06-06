package org.commonhaus.automation.admin.github;

import java.util.List;
import java.util.Objects;

import jakarta.inject.Inject;

import org.commonhaus.automation.admin.AdminDataCache;
import org.commonhaus.automation.admin.config.UserManagementConfig.AttestationConfig;
import org.commonhaus.automation.github.context.DataCommonComment;
import org.commonhaus.automation.github.context.DataCommonItem;
import org.commonhaus.automation.github.context.EventType;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkiverse.githubapp.event.Issue;
import io.quarkiverse.githubapp.event.IssueComment;
import io.quarkiverse.githubapp.event.Membership;
import io.quarkiverse.githubapp.event.Push;
import io.quarkus.logging.Log;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public class AdminGitHubEvents {

    @Inject
    AppContextService ctx;

    public void updateAttestationList(GitHub github, DynamicGraphQLClient graphQLClient,
            @Push GHEventPayload.Push pushEvent) {
        AttestationConfig cfg = ctx.attestationConfig();
        if (cfg == null) {
            return;
        }

        GHRepository repo = pushEvent.getRepository();
        String repoFullName = repo.getFullName();

        if (Objects.equals(repoFullName, cfg.repo())
                && pushEvent.getRef().endsWith("/main")
                && ctx.commitsContain(pushEvent, cfg.path())) {
            Log.debugf("updateAttestationList (push): %s", repo.getFullName());
            ScopedQueryContext qc = ctx.refreshScopedQueryContext(
                    github,
                    pushEvent.getRepository(),
                    pushEvent.getInstallation().getId())
                    .addExisting(graphQLClient);
            ctx.updateValidAttestations(qc);
        }
    }

    public void updateMembership(GitHub github, DynamicGraphQLClient graphQLClient,
            @Membership GHEventPayload.Membership membershipEvent) {
        AdminDataCache.KNOWN_USER.invalidate(membershipEvent.getMember().getLogin());
        ScopedQueryContext qc = ctx.refreshScopedQueryContext(
                github,
                membershipEvent.getRepository(),
                membershipEvent.getInstallation().getId())
                .addExisting(graphQLClient);

        qc.updateTeamList(membershipEvent.getOrganization(), membershipEvent.getTeam());
    }

    public void updateApplications(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @Issue GHEventPayload.Issue issueEvent) {
        String repoFullName = issueEvent.getRepository().getFullName();
        if (!repoFullName.equals(ctx.getDataStore())) {
            return;
        }
        ScopedQueryContext qc = ctx.refreshScopedQueryContext(
                github,
                issueEvent.getRepository(),
                issueEvent.getInstallation().getId())
                .addExisting(graphQLClient);

        GHIssue issue = issueEvent.getIssue();
        String nodeId = issue.getNodeId();
        DataCommonItem item = qc.getItem(EventType.issue, nodeId);

        Log.debugf("[%s] updateApplications: %s", qc.getLogId(),
                issue.getNumber(), item);
    }

    public void updateApplicationComments(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @IssueComment GHEventPayload.IssueComment issueCommentEvent) {
        String repoFullName = issueCommentEvent.getRepository().getFullName();
        if (!repoFullName.equals(ctx.getDataStore())) {
            return;
        }
        ScopedQueryContext qc = ctx.refreshScopedQueryContext(
                github,
                issueCommentEvent.getRepository(),
                issueCommentEvent.getInstallation().getId())
                .addExisting(graphQLClient);

        GHIssue issue = issueCommentEvent.getIssue();
        String nodeId = issue.getNodeId();
        List<DataCommonComment> comments = qc.getComments(nodeId, x -> true);

        Log.debugf("[%s] updateApplications: %s", qc.getLogId(),
                issue.getNumber(), comments);
    }
}
