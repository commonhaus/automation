package org.commonhaus.automation.github.voting;

import jakarta.inject.Inject;

import org.commonhaus.automation.RepositoryConfigFile;
import org.commonhaus.automation.github.EventQueryContext;
import org.commonhaus.automation.github.QueryHelper;
import org.commonhaus.automation.github.context.DataCommonComment;
import org.commonhaus.automation.github.context.EventData;
import org.commonhaus.automation.github.context.JsonAttribute;
import org.commonhaus.automation.github.voting.VoteEvent.ManualVoteEvent;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkiverse.githubapp.event.Discussion;
import io.quarkiverse.githubapp.event.DiscussionComment;
import io.quarkiverse.githubapp.event.IssueComment;
import io.quarkiverse.githubapp.event.PullRequest;
import io.quarkiverse.githubapp.event.PullRequestReview;
import io.quarkus.logging.Log;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;
import io.vertx.mutiny.core.eventbus.EventBus;

/**
 * Highlevel workflow to manage voting.
 * <p>
 * This acts as a mixin: stored with CFGH RepoInfo if voting is enabled.
 */
public class Voting {

    @Inject
    QueryHelper queryHelper;

    @Inject
    EventBus bus;

    /**
     * Called when there is a discussion event.
     *
     * @param event GitHubEvent (raw payload)
     * @param github GitHub API (connection instance)
     * @param graphQLClient GraphQL API (connection instance)
     * @param payload GitHub API parsed payload; connected GHRepository and
     *        GHOrganization
     * @param repoConfigFile Bot Repo Configuration (if exists)
     */
    void onDiscussionEvent(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @Discussion GHEventPayload.Discussion payload,
            @ConfigFile(RepositoryConfigFile.NAME) RepositoryConfigFile repoConfigFile) {

        VoteConfig votingConfig = VoteConfig.getVotingConfig(repoConfigFile);
        queryHelper.updateConfiguration(payload.getRepository(), repoConfigFile);
        if (votingConfig.isDisabled()) {
            return;
        }

        EventData eventData = new EventData(event, payload);
        EventQueryContext qc = queryHelper.newQueryContext(eventData, github, graphQLClient);

        // potentially multiple events at once to one event at a time...
        Log.debugf("[%s] voting.onDiscussionEvent: voting enabled; queue event", qc.getLogId());
        bus.send(VoteEvent.ADDRESS, new VoteEvent(qc, votingConfig, eventData));
    }

    /**
     * Called when there is a discussion comment event.
     *
     * @param event GitHubEvent (raw payload)
     * @param github GitHub API (connection instance)
     * @param graphQLClient GraphQL API (connection instance)
     * @param payload GitHub API parsed payload; connected GHRepository and
     *        GHOrganization
     * @param repoConfigFile Bot Repo Configuration (if exists)
     */
    void onDiscussionCommentEvent(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @DiscussionComment GHEventPayload.DiscussionComment payload,
            @ConfigFile(RepositoryConfigFile.NAME) RepositoryConfigFile repoConfigFile) {

        VoteConfig votingConfig = VoteConfig.getVotingConfig(repoConfigFile);
        queryHelper.updateConfiguration(payload.getRepository(), repoConfigFile);
        if (votingConfig.isDisabled()) {
            return;
        }

        EventData eventData = new EventData(event, payload);
        EventQueryContext qc = queryHelper.newQueryContext(eventData, github, graphQLClient);

        DataCommonComment comment = JsonAttribute.comment.commonCommentFrom(eventData.getJsonData());
        if (qc.isBot(comment.author.login)) {
            // skip bot comments
            return;
        }

        // potentially multiple events at once to one event at a time...
        Log.debugf("[%s] voting.onDiscussionCommentEvent: voting enabled; queue event", qc.getLogId());
        if (VoteEvent.isManualVoteResult(qc, votingConfig, comment)) {
            bus.send(VoteEvent.MANUAL_ADDRESS, new ManualVoteEvent(qc, votingConfig, eventData, comment));
        } else {
            bus.send(VoteEvent.ADDRESS, new VoteEvent(qc, votingConfig, eventData));
        }
    }

    /**
     * Called when there is a pull request event.
     *
     * @param event GitHubEvent (raw payload)
     * @param github GitHub API (connection instance)
     * @param graphQLClient GraphQL API (connection instance)
     * @param payload GitHub API parsed payload; connected GHRepository and
     *        GHOrganization
     * @param repoConfigFile Bot Repo Configuration (if exists)
     */
    void onPullRequestEvent(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @PullRequest GHEventPayload.PullRequest payload,
            @ConfigFile(RepositoryConfigFile.NAME) RepositoryConfigFile repoConfigFile) {

        VoteConfig votingConfig = VoteConfig.getVotingConfig(repoConfigFile);
        queryHelper.updateConfiguration(payload.getRepository(), repoConfigFile);
        if (votingConfig.isDisabled()) {
            return;
        }

        EventData eventData = new EventData(event, payload);
        EventQueryContext qc = queryHelper.newQueryContext(eventData, github, graphQLClient);

        // potentially multiple events at once to one event at a time...
        Log.debugf("[%s] voting.onPullRequestEvent: voting enabled; queue event", qc.getLogId());
        bus.send(VoteEvent.ADDRESS, new VoteEvent(qc, votingConfig, eventData));
    }

    /**
     * Called when there is a pull request review event.
     *
     * @param event GitHubEvent (raw payload)
     * @param github GitHub API (connection instance)
     * @param graphQLClient GraphQL API (connection instance)
     * @param payload GitHub API parsed payload; connected GHRepository and
     *        GHOrganization
     * @param repoConfigFile Bot Repo Configuration (if exists)
     */
    void onPullRequestReviewEvent(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @PullRequestReview GHEventPayload.PullRequestReview payload,
            @ConfigFile(RepositoryConfigFile.NAME) RepositoryConfigFile repoConfigFile) {

        VoteConfig votingConfig = VoteConfig.getVotingConfig(repoConfigFile);
        queryHelper.updateConfiguration(payload.getRepository(), repoConfigFile);
        if (votingConfig.isDisabled()) {
            return;
        }

        EventData eventData = new EventData(event, payload);
        EventQueryContext qc = queryHelper.newQueryContext(eventData, github, graphQLClient);

        // potentially multiple events at once to one event at a time...
        Log.debugf("[%s] voting.onPullRequestReviewEvent: voting enabled; queue event", qc.getLogId());
        bus.send(VoteEvent.ADDRESS, new VoteEvent(qc, votingConfig, eventData));
    }

    /**
     * Called when there is a issue or pull request comment event.
     *
     * @param event GitHubEvent (raw payload)
     * @param github GitHub API (connection instance)
     * @param graphQLClient GraphQL API (connection instance)
     * @param payload GitHub API parsed payload; connected GHRepository and
     *        GHOrganization
     * @param repoConfigFile Bot Repo Configuration (if exists)
     */
    void onIssueComment(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @IssueComment GHEventPayload.IssueComment payload,
            @ConfigFile(RepositoryConfigFile.NAME) RepositoryConfigFile repoConfigFile) {

        VoteConfig votingConfig = VoteConfig.getVotingConfig(repoConfigFile);
        queryHelper.updateConfiguration(payload.getRepository(), repoConfigFile);
        if (votingConfig.isDisabled()) {
            return;
        }

        EventData eventData = new EventData(event, payload);
        EventQueryContext qc = queryHelper.newQueryContext(eventData, github, graphQLClient);
        DataCommonComment comment = JsonAttribute.comment.commonCommentFrom(eventData.getJsonData());
        if (qc.isBot(comment.author.login)) {
            return;
        }

        // potentially multiple events at once to one event at a time...
        Log.debugf("[%s] voting.onIssueComment: voting enabled; queue event", qc.getLogId());
        if (VoteEvent.isManualVoteResult(qc, votingConfig, comment)) {
            bus.send(VoteEvent.MANUAL_ADDRESS, new ManualVoteEvent(qc, votingConfig, eventData, comment));
        } else {
            bus.send(VoteEvent.ADDRESS, new VoteEvent(qc, votingConfig, eventData));
        }
    }
}
