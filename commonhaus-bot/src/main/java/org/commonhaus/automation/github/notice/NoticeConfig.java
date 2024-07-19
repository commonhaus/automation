package org.commonhaus.automation.github.notice;

import java.util.HashMap;
import java.util.Map;

import org.commonhaus.automation.RepositoryConfigFile;
import org.commonhaus.automation.RepositoryConfigFile.RepositoryConfig;
import org.commonhaus.automation.github.actions.Action;
import org.commonhaus.automation.github.rules.RuleConfig;

import com.fasterxml.jackson.annotation.JsonProperty;

public class NoticeConfig extends RepositoryConfig {
    public static final NoticeConfig DISABLED = new NoticeConfig() {
        @Override
        public boolean isDisabled() {
            return true;
        }
    };

    public static NoticeConfig getNoticeConfig(RepositoryConfigFile repoConfigFile) {
        if (repoConfigFile == null || repoConfigFile.notice == null) {
            return DISABLED;
        }
        return repoConfigFile.notice;
    }

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
