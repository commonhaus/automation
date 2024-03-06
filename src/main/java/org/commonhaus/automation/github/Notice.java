package org.commonhaus.automation.github;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import org.commonhaus.automation.github.RepositoryAppConfig.CommonConfig;
import org.commonhaus.automation.github.RepositoryAppConfig.DiscussionConfig;
import org.commonhaus.automation.github.RepositoryAppConfig.PullRequestConfig;
import org.commonhaus.automation.github.actions.Action;
import org.commonhaus.automation.github.model.EventQueryContext;
import org.commonhaus.automation.github.model.QueryHelper;
import org.commonhaus.automation.github.rules.Rule;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GitHub;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkiverse.githubapp.event.Discussion;
import io.quarkiverse.githubapp.event.PullRequest;
import io.quarkus.logging.Log;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public class Notice {

    @Inject
    QueryHelper queryHelper;

    /**
     * Called when there is a discussion event.
     *
     * @param event GitHubEvent (raw payload)
     * @param github GitHub API (connection instance)
     * @param discussionPayload GitHub API parsed payload; connected GHRepository and GHOrganization
     * @param repoConfigFile CFGH RepoConfig (if exists)
     */
    void onDiscussionEvent(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @Discussion GHEventPayload.Discussion discussionPayload,
            @ConfigFile(RepositoryAppConfig.NAME) RepositoryAppConfig.File repoConfigFile) {

        Notice.Config noticeConfig = getNoticeConfig(repoConfigFile);
        if (noticeConfig.isEnabled()) {
            return;
        }

        EventData eventData = new EventData(event, discussionPayload);
        EventQueryContext queryContext = queryHelper.newQueryContext(eventData, github, graphQLClient);
        Set<String> desiredActions = findMatchingActions(queryContext, noticeConfig.discussion.rules);

        Log.infof("[%s] notice.onDiscussionEvent: triggered (%s) actions: %s", eventData.getLogId(),
                desiredActions.size(), desiredActions);

        applyMatchingActions("notice.onDiscussionEvent", queryContext, desiredActions, noticeConfig.actions);
    }

    /**
     * Called when there is a pull request event.
     *
     * @param event GitHubEvent (raw payload)
     * @param github GitHub API (connection instance)
     * @param pullRequestPayload GitHub API parsed payload
     * @param repoConfigFile CFGH RepoConfig (if exists)
     */
    void onPullRequestEvent(GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient,
            @PullRequest GHEventPayload.PullRequest pullRequestPayload,
            @ConfigFile(RepositoryAppConfig.NAME) RepositoryAppConfig.File repoConfigFile) {

        Notice.Config noticeConfig = getNoticeConfig(repoConfigFile);
        if (noticeConfig.isEnabled()) {
            return;
        }

        EventData eventData = new EventData(event, pullRequestPayload);
        EventQueryContext queryContext = queryHelper.newQueryContext(eventData, github, graphQLClient);

        Set<String> desiredActions = findMatchingActions(queryContext, noticeConfig.pullRequest.rules);
        Log.infof("[%s] notice.onPullRequestEvent: triggered (%s) actions: %s", eventData.getLogId(),
                desiredActions.size(), desiredActions);

        applyMatchingActions("notice.onPullRequestEvent", queryContext, desiredActions, noticeConfig.actions);
    }

    private Set<String> findMatchingActions(EventQueryContext queryContext, List<Rule> rules) {
        Set<String> actions = new HashSet<>();
        for (Rule rule : rules) {
            if (rule.matches(queryContext)) {
                actions.addAll(rule.then);
            }
        }
        return actions;
    }

    private void applyMatchingActions(String method, EventQueryContext queryContext,
            Set<String> desiredActions, Map<String, Action> actionsMap) {
        if (desiredActions.isEmpty()) {
            return;
        }
        for (String actionName : desiredActions) {
            Action action = actionsMap.get(actionName);
            if (action == null) {
                Log.warnf("[%s] %s: Action '%s' not found",
                        queryContext.getLogId(),
                        method, actionName);
                continue;
            }
            action.apply(queryContext);
        }
    }

    static Notice.Config getNoticeConfig(RepositoryAppConfig.File repoConfigFile) {
        if (repoConfigFile == null) {
            return Notice.Config.DISABLED;
        }
        return repoConfigFile.notice;
    }

    public static class Config extends CommonConfig {
        public static final Config DISABLED = new Config() {
            @Override
            public boolean isEnabled() {
                return true;
            }
        };

        @Override
        public boolean isEnabled() {
            return super.isEnabled() || actions.isEmpty();
        }

        public DiscussionConfig discussion;

        @JsonProperty("pull_request")
        public PullRequestConfig pullRequest;

        public final Map<String, Action> actions = new HashMap<>();
    }
}
