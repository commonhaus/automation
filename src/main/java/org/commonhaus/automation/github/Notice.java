package org.commonhaus.automation.github;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import org.commonhaus.automation.github.QueryHelper.QueryContext;
import org.commonhaus.automation.github.RepositoryAppConfig.CommonConfig;
import org.commonhaus.automation.github.RepositoryAppConfig.DiscussionConfig;
import org.commonhaus.automation.github.RepositoryAppConfig.PullRequestConfig;
import org.commonhaus.automation.github.actions.Action;
import org.commonhaus.automation.github.rules.Rule;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GitHub;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkiverse.githubapp.event.Discussion;
import io.quarkiverse.githubapp.event.PullRequest;
import io.quarkus.logging.Log;

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
    void onDiscussionEvent(GitHubEvent event, GitHub github,
            @Discussion GHEventPayload.Discussion discussionPayload,
            @ConfigFile(RepositoryAppConfig.NAME) RepositoryAppConfig.File repoConfigFile) {

        Notice.Config noticeConfig = getNoticeConfig(repoConfigFile);
        if (!noticeConfig.isEnabled()) {
            return;
        }

        QueryContext queryContext = queryHelper.newQueryContext(new EventData(event, discussionPayload), github);
        if (queryContext.isFromMe()) {
            Log.debugf("notice.onDiscussionEvent (%s): Bot sender detected, skipping", event.getEventAction());
            //return;
        }
        Log.debugf("notice.onDiscussionEvent (%s): %s", noticeConfig.isEnabled(), event.getEventAction());

        Set<String> actions = findMatchingActions(queryContext, noticeConfig.discussion.rules);
        Log.infof("notice.onDiscussionEvent (%s): Discussion #%s triggered (%s) actions: %s",
                event.getEventAction(), discussionPayload.getDiscussion().getNumber(), actions.size(), actions);

        applyMatchingActions("notice.onDiscussionEvent", event.getEventAction(), queryContext,
                actions, noticeConfig.actions);
    }

    /**
     * Called when there is a discussion event.
     *
     * @param event GitHubEvent (raw payload)
     * @param github GitHub API (connection instance)
     * @param pullRequestPayload GitHub API parsed payload
     * @param repoConfigFile CFGH RepoConfig (if exists)
     */
    void onPullRequestEvent(GitHubEvent event, GitHub github,
            @PullRequest GHEventPayload.PullRequest pullRequestPayload,
            @ConfigFile(RepositoryAppConfig.NAME) RepositoryAppConfig.File repoConfigFile) {

        Notice.Config noticeConfig = getNoticeConfig(repoConfigFile);
        if (!noticeConfig.isEnabled()) {
            return;
        }

        QueryContext queryContext = queryHelper.newQueryContext(new EventData(event, pullRequestPayload), github);
        if (queryContext.isFromMe()) {
            Log.debugf("notice.onPullRequestEvent (%s): Bot sender detected, skipping", event.getEventAction());
            //return;
        }
        Log.debugf("notice.onPullRequestEvent (%s): %s", noticeConfig.isEnabled(), event.getEventAction());

        Set<String> desiredActions = findMatchingActions(queryContext, noticeConfig.pullRequest.rules);
        Log.infof("notice.onPullRequestEvent (%s): Pull Request #%s triggered (%s) actions: %s",
                event.getEventAction(), pullRequestPayload.getPullRequest().getNumber(), desiredActions.size(), desiredActions);

        applyMatchingActions("notice.onPullRequestEvent", event.getEventAction(), queryContext,
                desiredActions, noticeConfig.actions);
    }

    private Set<String> findMatchingActions(QueryContext queryContext, List<Rule> rules) {
        Set<String> actions = new HashSet<>();
        for (Rule rule : rules) {
            if (rule.matches(queryContext)) {
                actions.addAll(rule.then);
            }
        }
        return actions;
    }

    private void applyMatchingActions(String method, String eventAction, QueryContext queryContext,
            Set<String> desiredActions, Map<String, Action> actionsMap) {
        if (desiredActions.isEmpty()) {
            return;
        }
        for (String actionName : desiredActions) {
            Action action = actionsMap.get(actionName);
            if (action == null) {
                Log.warnf("%s (%s): Action '%s' not found", method, eventAction, actionName);
                continue;
            }
            action.apply(queryContext);
        }
    }

    static Notice.Config getNoticeConfig(RepositoryAppConfig.File repoConfigFile) {
        if (repoConfigFile == null || repoConfigFile.notice == null) {
            return Notice.Config.DISABLED;
        }
        return repoConfigFile.notice;
    }

    public static class Config extends CommonConfig {
        public static final Config DISABLED = new Config() {
            @Override
            public boolean isEnabled() {
                return false;
            }
        };

        public DiscussionConfig discussion;

        @JsonProperty("pull_request")
        public PullRequestConfig pullRequest;
    }
}
