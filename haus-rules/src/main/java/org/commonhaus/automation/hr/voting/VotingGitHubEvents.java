package org.commonhaus.automation.hr.voting;

import jakarta.inject.Inject;

import org.commonhaus.automation.github.context.DataCommonComment;
import org.commonhaus.automation.github.context.EventData;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.github.context.GitHubTeamService;
import org.commonhaus.automation.github.context.JsonAttribute;
import org.commonhaus.automation.hr.AppContextService;
import org.commonhaus.automation.hr.EventQueryContext;
import org.commonhaus.automation.hr.config.ConfigWatcher;
import org.commonhaus.automation.hr.config.HausRulesConfig;
import org.commonhaus.automation.hr.config.VoteConfig;
import org.commonhaus.automation.queue.PeriodicUpdateQueue;
import org.kohsuke.github.GHEventPayload;

import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkiverse.githubapp.event.Discussion;
import io.quarkiverse.githubapp.event.DiscussionComment;
import io.quarkiverse.githubapp.event.Issue;
import io.quarkiverse.githubapp.event.IssueComment;
import io.quarkiverse.githubapp.event.PullRequest;
import io.quarkiverse.githubapp.event.PullRequestReview;

public class VotingGitHubEvents {

    @Inject
    AppContextService ctx;

    @Inject
    ConfigWatcher configWatcher;

    @Inject
    PeriodicUpdateQueue periodicUpdate;

    @Inject
    GitHubTeamService teamService;

    @Inject
    VoteProcessor voteProcessor;

    /**
     * Called when there is a discussion event.
     *
     * @param event GitHubEvent (raw payload)
     * @param payload GitHub API parsed payload; connected GHRepository and
     *        GHOrganization
     * @param repoConfigFile Bot Repo Configuration (if exists)
     */
    void onDiscussionEvent(GitHubEvent event,
            @Discussion GHEventPayload.Discussion payload,
            @ConfigFile(HausRulesConfig.NAME) HausRulesConfig repoConfigFile) {
        processVoteEvent(repoConfigFile, new EventData(event, payload));
    }

    /**
     * Called when there is a discussion comment event.
     *
     * @param event GitHubEvent (raw payload)
     * @param payload GitHub API parsed payload; connected GHRepository and
     *        GHOrganization
     * @param repoConfigFile Bot Repo Configuration (if exists)
     */
    void onDiscussionCommentEvent(GitHubEvent event,
            @DiscussionComment GHEventPayload.DiscussionComment payload,
            @ConfigFile(HausRulesConfig.NAME) HausRulesConfig repoConfigFile) {
        processVoteEvent(repoConfigFile, new EventData(event, payload));
    }

    /**
     * Called when there is an issue event.
     *
     * @param event GitHubEvent (raw payload)
     * @param payload GitHub API parsed payload; connected GHRepository and
     *        GHOrganization
     * @param repoConfigFile Bot Repo Configuration (if exists)
     */
    void onIssueEvent(GitHubEvent event,
            @Issue GHEventPayload.Issue payload,
            @ConfigFile(HausRulesConfig.NAME) HausRulesConfig repoConfigFile) {
        processVoteEvent(repoConfigFile, new EventData(event, payload));
    }

    /**
     * Called when there is a pull request event.
     *
     * @param event GitHubEvent (raw payload)
     * @param payload GitHub API parsed payload; connected GHRepository and
     *        GHOrganization
     * @param repoConfigFile Bot Repo Configuration (if exists)
     */
    void onPullRequestEvent(GitHubEvent event,
            @PullRequest GHEventPayload.PullRequest payload,
            @ConfigFile(HausRulesConfig.NAME) HausRulesConfig repoConfigFile) {
        processVoteEvent(repoConfigFile, new EventData(event, payload));
    }

    /**
     * Called when there is a pull request review event.
     *
     * @param event GitHubEvent (raw payload)
     * @param payload GitHub API parsed payload; connected GHRepository and
     *        GHOrganization
     * @param repoConfigFile Bot Repo Configuration (if exists)
     */
    void onPullRequestReviewEvent(GitHubEvent event,
            @PullRequestReview GHEventPayload.PullRequestReview payload,
            @ConfigFile(HausRulesConfig.NAME) HausRulesConfig repoConfigFile) {
        processVoteEvent(repoConfigFile, new EventData(event, payload));
    }

    /**
     * Called when there is a issue or pull request comment event.
     *
     * @param event GitHubEvent (raw payload)
     * @param payload GitHub API parsed payload; connected GHRepository and
     *        GHOrganization
     * @param repoConfigFile Bot Repo Configuration (if exists)
     */
    void onIssueComment(GitHubEvent event,
            @IssueComment GHEventPayload.IssueComment payload,
            @ConfigFile(HausRulesConfig.NAME) HausRulesConfig repoConfigFile) {
        processVoteEvent(repoConfigFile, new EventData(event, payload));
    }

    private void processVoteEvent(HausRulesConfig repoConfigFile, EventData eventData) {
        VoteConfig votingConfig = configWatcher.updateVoteConfig(
                eventData.getRepoFullName(),
                repoConfigFile);

        if (votingConfig.isDisabled()) {
            return;
        }

        if (VoteEvent.eventToType(eventData) == EventType.unknown) {
            return;
        }

        if (eventData.getEventType() == EventType.discussion_comment || eventData.getEventType() == EventType.issue_comment) {
            EventQueryContext qc = new EventQueryContext(ctx, eventData);
            DataCommonComment comment = JsonAttribute.comment.commonCommentFrom(eventData.getJsonData());
            if (qc.isBot(comment.author.login)) {
                // skip bot comment events
                return;
            }
            if (voteProcessor.isManualVoteResult(qc, votingConfig, comment)) {
                // Make a note if this item (discussion, etc) has a manual vote result
                // so we can look for all manual vote results later if the item is not also closed.
                VoteQueryCache.MANUAL_RESULT_COMMENT_ID.put(eventData.getNodeId(), comment.id);
            }
        }

        VoteEvent voteEvent = voteProcessor.createVoteEvent(eventData);
        periodicUpdate.queue(voteEvent.getTaskGroup(), () -> {
            voteProcessor.reconcileVoteEvent(voteEvent);
        });
    }
}
