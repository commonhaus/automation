package org.commonhaus.automation.github;

import java.util.Map;

import jakarta.inject.Inject;

import org.commonhaus.automation.github.RepositoryAppConfig.CommonConfig;
import org.commonhaus.automation.github.model.EventQueryContext;
import org.commonhaus.automation.github.model.QueryHelper;
import org.commonhaus.automation.github.voting.VoteEvent;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkiverse.githubapp.event.Discussion;
import io.quarkiverse.githubapp.event.DiscussionComment;
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
     * @param payload GitHub API parsed payload; connected GHRepository and GHOrganization
     * @param repoConfigFile Bot Repo Configuration (if exists)
     */
    void onDiscussionEvent(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @Discussion GHEventPayload.Discussion payload,
            @ConfigFile(RepositoryAppConfig.NAME) RepositoryAppConfig.File repoConfigFile) {

        Voting.Config votingConfig = getVotingConfig(repoConfigFile);
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
     * @param payload GitHub API parsed payload; connected GHRepository and GHOrganization
     * @param repoConfigFile Bot Repo Configuration (if exists)
     */
    void onDiscussionCommentEvent(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @DiscussionComment GHEventPayload.DiscussionComment payload,
            @ConfigFile(RepositoryAppConfig.NAME) RepositoryAppConfig.File repoConfigFile) {

        Voting.Config votingConfig = getVotingConfig(repoConfigFile);
        if (votingConfig.isDisabled()) {
            return;
        }

        EventData eventData = new EventData(event, payload);
        EventQueryContext qc = queryHelper.newQueryContext(eventData, github, graphQLClient);

        // potentially multiple events at once to one event at a time...
        Log.debugf("[%s] voting.onDiscussionCommentEvent: voting enabled; queue event", qc.getLogId());
        bus.send(VoteEvent.ADDRESS, new VoteEvent(qc, votingConfig, eventData));
    }

    /**
     * Called when there is a pull request event.
     *
     * @param event GitHubEvent (raw payload)
     * @param github GitHub API (connection instance)
     * @param graphQLClient GraphQL API (connection instance)
     * @param payload GitHub API parsed payload; connected GHRepository and GHOrganization
     * @param repoConfigFile Bot Repo Configuration (if exists)
     */
    void onPullRequestEvent(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @PullRequest GHEventPayload.PullRequest payload,
            @ConfigFile(RepositoryAppConfig.NAME) RepositoryAppConfig.File repoConfigFile) {

        Voting.Config votingConfig = getVotingConfig(repoConfigFile);
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
     * @param payload GitHub API parsed payload; connected GHRepository and GHOrganization
     * @param repoConfigFile Bot Repo Configuration (if exists)
     */
    void onPullRequestReviewEvent(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @PullRequestReview GHEventPayload.PullRequestReview payload,
            @ConfigFile(RepositoryAppConfig.NAME) RepositoryAppConfig.File repoConfigFile) {

        Voting.Config votingConfig = getVotingConfig(repoConfigFile);
        if (votingConfig.isDisabled()) {
            return;
        }

        EventData eventData = new EventData(event, payload);
        EventQueryContext qc = queryHelper.newQueryContext(eventData, github, graphQLClient);

        // potentially multiple events at once to one event at a time...
        Log.debugf("[%s] voting.onPullRequestReviewEvent: voting enabled; queue event", qc.getLogId());
        bus.send(VoteEvent.ADDRESS, new VoteEvent(qc, votingConfig, eventData));
    }

    // How many votes are required for a vote to count?
    public enum Threshold {
        all,
        majority,
        supermajority
    }

    public static class StatusLinks {
        public String badge;
        public String page;
    }

    public static Voting.Config getVotingConfig(RepositoryAppConfig.File repoConfigFile) {
        if (repoConfigFile == null) {
            return Voting.Config.DISABLED;
        }
        return repoConfigFile.voting;
    }

    public static class Config extends CommonConfig {
        public static final Config DISABLED = new Config() {
            @Override
            public boolean isDisabled() {
                return true;
            }
        };

        public String[] error_email_address;
        public Map<String, Threshold> votingThreshold;
        public StatusLinks status;

        public boolean sendErrorEmail() {
            return error_email_address != null && error_email_address.length > 0;
        }

        public Threshold votingThreshold(String group) {
            if (votingThreshold == null) {
                return Threshold.all;
            }
            return votingThreshold.getOrDefault(group, Threshold.all);
        }
    }
}
