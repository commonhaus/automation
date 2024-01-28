package org.commonhaus.automation.github;

import java.util.HashSet;
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
            @Discussion.Created @Discussion.CategoryChanged @Discussion.Edited GHEventPayload.Discussion discussionPayload,
            @ConfigFile(RepositoryAppConfig.NAME) RepositoryAppConfig.File repoConfigFile) {

        Notice.Config noticeConfig = getNoticeConfig(repoConfigFile);
        if (!noticeConfig.isEnabled()) {
            return;
        }

        final EventData initialData = new EventData(event, discussionPayload);
        QueryContext queryContext = queryHelper.newQueryContext(initialData, github);

        Set<String> actions = new HashSet<>();
        for (Rule rule : noticeConfig.discussion.rules) {
            if (rule.matches(queryContext)) {
                actions.addAll(rule.then);
            }
        }
        Log.debugf("notice.onDiscussionEvent (%s): Discussion #%s triggered (%s) actions: %s",
                event.getEventAction(), discussionPayload.getDiscussion().getNumber(), actions.size(), actions);

        if (actions.isEmpty()) {
            return;
        }

        for (String actionName : actions) {
            Action action = noticeConfig.actions.get(actionName);
            if (action == null) {
                Log.warnf("notice.onDiscussionEvent (%s): Action '%s' not found", event.getEventAction(), actionName);
                continue;
            }
            action.apply(queryContext);
        }
    }

    /**
     * Called when there is a discussion event.
     *
     * @param event GitHubEvent (raw payload)
     * @param github GitHub API (connection instance)
     * @param discussion GitHub API parsed payload
     * @param repoConfigFile CFGH RepoConfig (if exists)
     */
    void onPullRequestEvent(GitHubEvent event, GitHub github,
            @PullRequest GHEventPayload.PullRequest discussion,
            @ConfigFile(RepositoryAppConfig.NAME) RepositoryAppConfig.File repoConfigFile) {
        Notice.Config noticeConfig = getNoticeConfig(repoConfigFile);
        Log.debugf("notice.onPullRequestEvent (%s): %s %s", noticeConfig.isEnabled(), event.getEventAction(),
                event.getPayload());
        if (!noticeConfig.isEnabled()) {
            return;
        }

        QueryContext queryContext = queryHelper.newQueryContext(new EventData(event, discussion), github);
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
