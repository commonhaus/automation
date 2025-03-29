package org.commonhaus.automation.hr.config;

import java.util.HashMap;
import java.util.Map;

import org.commonhaus.automation.hr.actions.Action;
import org.commonhaus.automation.hr.config.HausRulesConfig.RepositoryConfig;

import com.fasterxml.jackson.annotation.JsonProperty;

public class NoticeConfig extends RepositoryConfig {
    public static final NoticeConfig DISABLED = new NoticeConfig() {
        @Override
        public boolean isDisabled() {
            return true;
        }
    };

    @Override
    public boolean isDisabled() {
        return super.isDisabled() || actions.isEmpty();
    }

    public RuleConfig discussion;

    @JsonProperty("discussion_comment")
    public RuleConfig discussionComment;

    public RuleConfig issue;

    @JsonProperty("issue_comment")
    public RuleConfig issueComment;

    @JsonProperty("pull_request")
    public RuleConfig pullRequest;

    public final Map<String, Action> actions = new HashMap<>();
}
