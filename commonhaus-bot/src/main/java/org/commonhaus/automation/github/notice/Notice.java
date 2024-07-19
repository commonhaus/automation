package org.commonhaus.automation.github.notice;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import org.commonhaus.automation.RepositoryConfigFile;
import org.commonhaus.automation.github.AppContextService;
import org.commonhaus.automation.github.EventQueryContext;
import org.commonhaus.automation.github.actions.Action;
import org.commonhaus.automation.github.context.EventData;
import org.commonhaus.automation.github.rules.Rule;
import org.commonhaus.automation.github.rules.RuleConfig;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkiverse.githubapp.event.Discussion;
import io.quarkiverse.githubapp.event.DiscussionComment;
import io.quarkiverse.githubapp.event.Issue;
import io.quarkiverse.githubapp.event.IssueComment;
import io.quarkiverse.githubapp.event.PullRequest;
import io.quarkus.logging.Log;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public class Notice {

    @Inject
    AppContextService queryHelper;

    /**
     * Called when there is a discussion event.
     *
     * @param github GitHub API (connection instance)
     * @param graphQLClient GraphQL API (connection instance)
     * @param payload GitHub API parsed payload; connected GHRepository and GHOrganization
     * @param repoConfigFile Bot Repo Configuration (if exists)
     */
    void onDiscussionEvent(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @Discussion GHEventPayload.Discussion payload,
            @ConfigFile(RepositoryConfigFile.NAME) RepositoryConfigFile repoConfigFile) {

        NoticeConfig noticeConfig = NoticeConfig.getNoticeConfig(repoConfigFile);
        queryHelper.updateConfiguration(payload.getRepository(), repoConfigFile);

        RuleConfig ruleConfig = noticeConfig.discussion;
        if (noticeConfig.isDisabled() || ruleConfig == null) {
            return;
        }

        EventData eventData = new EventData(event, payload);
        EventQueryContext qc = queryHelper.newQueryContext(eventData, github, graphQLClient);
        Set<String> desiredActions = findMatchingActions(qc, ruleConfig.rules);

        Log.infof("[%s] notice.onDiscussionEvent: triggered (%s) actions: %s", eventData.getLogId(),
                desiredActions.size(), desiredActions);

        applyMatchingActions("notice.onDiscussionEvent", qc, desiredActions, noticeConfig.actions);
    }

    /**
     * Called when there is a discussioncomment event.
     *
     * @param github GitHub API (connection instance)
     * @param graphQLClient GraphQL API (connection instance)
     * @param payload GitHub API parsed payload; connected GHRepository and GHOrganization
     * @param repoConfigFile Bot Repo Configuration (if exists)
     */
    void onDiscussionCommentEvent(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @DiscussionComment GHEventPayload.DiscussionComment payload,
            @ConfigFile(RepositoryConfigFile.NAME) RepositoryConfigFile repoConfigFile) {

        NoticeConfig noticeConfig = NoticeConfig.getNoticeConfig(repoConfigFile);
        queryHelper.updateConfiguration(payload.getRepository(), repoConfigFile);

        RuleConfig ruleConfig = noticeConfig.discussionComment;
        if (noticeConfig.isDisabled() || ruleConfig == null) {
            return;
        }

        EventData eventData = new EventData(event, payload);
        EventQueryContext qc = queryHelper.newQueryContext(eventData, github, graphQLClient);
        Set<String> desiredActions = findMatchingActions(qc, ruleConfig.rules);

        Log.infof("[%s] notice.onDiscussionCommentEvent: triggered (%s) actions: %s", eventData.getLogId(),
                desiredActions.size(), desiredActions);

        applyMatchingActions("notice.onDiscussionCommentEvent", qc, desiredActions, noticeConfig.actions);
    }

    /**
     * Called when there is an issue event.
     *
     * @param github GitHub API (connection instance)
     * @param graphQLClient GraphQL API (connection instance)
     * @param payload GitHub API parsed payload; connected GHRepository and GHOrganization
     * @param repoConfigFile Bot Repo Configuration (if exists)
     */
    void onIssueEvent(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @Issue GHEventPayload.Issue payload,
            @ConfigFile(RepositoryConfigFile.NAME) RepositoryConfigFile repoConfigFile) {

        NoticeConfig noticeConfig = NoticeConfig.getNoticeConfig(repoConfigFile);
        queryHelper.updateConfiguration(payload.getRepository(), repoConfigFile);

        RuleConfig ruleConfig = noticeConfig.issue;
        if (noticeConfig.isDisabled() || ruleConfig == null) {
            return;
        }

        EventData eventData = new EventData(event, payload);
        EventQueryContext qc = queryHelper.newQueryContext(eventData, github, graphQLClient);

        Set<String> desiredActions = findMatchingActions(qc, ruleConfig.rules);
        Log.infof("[%s] notice.onIssueEvent: triggered (%s) actions: %s", eventData.getLogId(),
                desiredActions.size(), desiredActions);

        applyMatchingActions("notice.onIssueEvent", qc, desiredActions, noticeConfig.actions);
    }

    /**
     * Called when there is a pull request event.
     *
     * @param github GitHub API (connection instance)
     * @param graphQLClient GraphQL API (connection instance)
     * @param payload GitHub API parsed payload; connected GHRepository and GHOrganization
     * @param repoConfigFile Bot Repo Configuration (if exists)
     */
    void onPullRequestEvent(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @PullRequest GHEventPayload.PullRequest payload,
            @ConfigFile(RepositoryConfigFile.NAME) RepositoryConfigFile repoConfigFile) {

        NoticeConfig noticeConfig = NoticeConfig.getNoticeConfig(repoConfigFile);
        queryHelper.updateConfiguration(payload.getRepository(), repoConfigFile);

        RuleConfig ruleConfig = noticeConfig.pullRequest;
        if (noticeConfig.isDisabled() || ruleConfig == null) {
            return;
        }

        EventData eventData = new EventData(event, payload);
        EventQueryContext qc = queryHelper.newQueryContext(eventData, github, graphQLClient);

        Set<String> desiredActions = findMatchingActions(qc, ruleConfig.rules);
        Log.infof("[%s] notice.onPullRequestEvent: triggered (%s) actions: %s", eventData.getLogId(),
                desiredActions.size(), desiredActions);

        applyMatchingActions("notice.onPullRequestEvent", qc, desiredActions, noticeConfig.actions);
    }

    /**
     * Called when there is a comment on an issue or pull request
     *
     * @param github GitHub API (connection instance)
     * @param graphQLClient GraphQL API (connection instance)
     * @param payload GitHub API parsed payload; connected GHRepository and GHOrganization
     * @param repoConfigFile Bot Repo Configuration (if exists)
     */
    void onIssueCommentEvent(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @IssueComment GHEventPayload.IssueComment payload,
            @ConfigFile(RepositoryConfigFile.NAME) RepositoryConfigFile repoConfigFile) {

        NoticeConfig noticeConfig = NoticeConfig.getNoticeConfig(repoConfigFile);
        queryHelper.updateConfiguration(payload.getRepository(), repoConfigFile);

        RuleConfig ruleConfig = noticeConfig.issueComment;
        if (noticeConfig.isDisabled() || ruleConfig == null) {
            return;
        }

        EventData eventData = new EventData(event, payload);
        EventQueryContext qc = queryHelper.newQueryContext(eventData, github, graphQLClient);

        Set<String> desiredActions = findMatchingActions(qc, ruleConfig.rules);
        Log.infof("[%s] notice.onIssueCommentEvent: triggered (%s) actions: %s", eventData.getLogId(),
                desiredActions.size(), desiredActions);

        applyMatchingActions("notice.onIssueCommentEvent", qc, desiredActions, noticeConfig.actions);
    }

    private Set<String> findMatchingActions(EventQueryContext qc, List<Rule> rules) {
        Set<String> actions = new HashSet<>();
        for (Rule rule : rules) {
            if (rule.matches(qc)) {
                actions.addAll(rule.then);
            }
        }
        return actions;
    }

    private void applyMatchingActions(String method, EventQueryContext qc,
            Set<String> desiredActions, Map<String, Action> actionsMap) {
        if (desiredActions.isEmpty()) {
            return;
        }
        for (String actionName : desiredActions) {
            Action action = actionsMap.get(actionName);
            if (action == null) {
                Log.warnf("[%s] %s: Action '%s' not found",
                        qc.getLogId(),
                        method, actionName);
                continue;
            }
            action.apply(qc);
        }
    }
}
