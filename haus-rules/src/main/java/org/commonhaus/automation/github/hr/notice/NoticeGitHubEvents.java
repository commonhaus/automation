package org.commonhaus.automation.github.hr.notice;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import org.commonhaus.automation.github.context.EventData;
import org.commonhaus.automation.github.hr.AppContextService;
import org.commonhaus.automation.github.hr.EventQueryContext;
import org.commonhaus.automation.github.hr.actions.Action;
import org.commonhaus.automation.github.hr.config.ConfigWatcher;
import org.commonhaus.automation.github.hr.config.HausRulesConfig;
import org.commonhaus.automation.github.hr.config.NoticeConfig;
import org.commonhaus.automation.github.hr.config.RuleConfig;
import org.commonhaus.automation.github.hr.rules.Rule;
import org.kohsuke.github.GHEventPayload;

import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkiverse.githubapp.event.Discussion;
import io.quarkiverse.githubapp.event.DiscussionComment;
import io.quarkiverse.githubapp.event.Issue;
import io.quarkiverse.githubapp.event.IssueComment;
import io.quarkiverse.githubapp.event.PullRequest;
import io.quarkus.logging.Log;

public class NoticeGitHubEvents {

    @Inject
    AppContextService ctx;

    @Inject
    ConfigWatcher configWatcher;

    /**
     * Called when there is a discussion event.
     *
     * @param event GitHub Event
     * @param payload GitHub API parsed payload; connected GHRepository and GHOrganization
     * @param repoConfigFile Bot Repo Configuration (if exists)
     */
    void onDiscussionEvent(GitHubEvent event,
            @Discussion GHEventPayload.Discussion payload,
            @ConfigFile(HausRulesConfig.NAME) HausRulesConfig repoConfigFile) {
        String repoFullName = event.getRepository().orElse(null);
        if (repoFullName == null) {
            Log.warnf("onDiscussionEvent: Missing repository information");
            return;
        }
        NoticeConfig noticeConfig = configWatcher.updateNoticeConfig(repoFullName, repoConfigFile);
        RuleConfig ruleConfig = noticeConfig.discussion;
        processNoticeEvent(noticeConfig, ruleConfig, new EventData(event, payload));
    }

    /**
     * Called when there is a discussioncomment event.
     *
     * @param event GitHub Event
     * @param payload GitHub API parsed payload; connected GHRepository and GHOrganization
     * @param repoConfigFile Bot Repo Configuration (if exists)
     */
    void onDiscussionCommentEvent(GitHubEvent event,
            @DiscussionComment GHEventPayload.DiscussionComment payload,
            @ConfigFile(HausRulesConfig.NAME) HausRulesConfig repoConfigFile) {
        String repoFullName = event.getRepository().orElse(null);
        if (repoFullName == null) {
            Log.warnf("onDiscussionEvent: Missing repository information");
            return;
        }
        NoticeConfig noticeConfig = configWatcher.updateNoticeConfig(repoFullName, repoConfigFile);
        RuleConfig ruleConfig = noticeConfig.discussionComment;
        processNoticeEvent(noticeConfig, ruleConfig, new EventData(event, payload));
    }

    /**
     * Called when there is an issue event.
     *
     * @param event GitHub Event
     * @param payload GitHub API parsed payload; connected GHRepository and GHOrganization
     * @param repoConfigFile Bot Repo Configuration (if exists)
     */
    void onIssueEvent(GitHubEvent event,
            @Issue GHEventPayload.Issue payload,
            @ConfigFile(HausRulesConfig.NAME) HausRulesConfig repoConfigFile) {
        String repoFullName = event.getRepository().orElse(null);
        if (repoFullName == null) {
            Log.warnf("onDiscussionEvent: Missing repository information");
            return;
        }
        NoticeConfig noticeConfig = configWatcher.updateNoticeConfig(repoFullName, repoConfigFile);
        RuleConfig ruleConfig = noticeConfig.issue;
        processNoticeEvent(noticeConfig, ruleConfig, new EventData(event, payload));
    }

    /**
     * Called when there is a pull request event.
     *
     * @param event GitHub Event
     * @param payload GitHub API parsed payload; connected GHRepository and GHOrganization
     * @param repoConfigFile Bot Repo Configuration (if exists)
     */
    void onPullRequestEvent(GitHubEvent event,
            @PullRequest GHEventPayload.PullRequest payload,
            @ConfigFile(HausRulesConfig.NAME) HausRulesConfig repoConfigFile) {
        String repoFullName = event.getRepository().orElse(null);
        if (repoFullName == null) {
            Log.warnf("onDiscussionEvent: Missing repository information");
            return;
        }
        NoticeConfig noticeConfig = configWatcher.updateNoticeConfig(repoFullName, repoConfigFile);
        RuleConfig ruleConfig = noticeConfig.pullRequest;
        processNoticeEvent(noticeConfig, ruleConfig, new EventData(event, payload));
    }

    /**
     * Called when there is a comment on an issue or pull request
     *
     * @param event GitHub Event
     * @param payload GitHub API parsed payload; connected GHRepository and GHOrganization
     * @param repoConfigFile Bot Repo Configuration (if exists)
     */
    void onIssueCommentEvent(GitHubEvent event,
            @IssueComment GHEventPayload.IssueComment payload,
            @ConfigFile(HausRulesConfig.NAME) HausRulesConfig repoConfigFile) {
        String repoFullName = event.getRepository().orElse(null);
        if (repoFullName == null) {
            Log.warnf("onDiscussionEvent: Missing repository information");
            return;
        }
        NoticeConfig noticeConfig = configWatcher.updateNoticeConfig(repoFullName, repoConfigFile);
        RuleConfig ruleConfig = noticeConfig.issueComment;
        processNoticeEvent(noticeConfig, ruleConfig, new EventData(event, payload));
    }

    private void processNoticeEvent(NoticeConfig noticeConfig, RuleConfig ruleConfig, EventData eventData) {
        if (noticeConfig.isDisabled() || ruleConfig == null) {
            return;
        }
        EventQueryContext qc = new EventQueryContext(ctx, eventData);

        Set<String> desiredActions = findMatchingActions(qc, ruleConfig.rules);
        Log.infof("[%s] processNoticeEvent: triggered (%s) actions: %s", eventData.getLogId(),
                desiredActions.size(), desiredActions);

        applyMatchingActions("processNoticeEvent", qc, desiredActions, noticeConfig.actions);
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
