package org.commonhaus.automation.github.hr;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.commonhaus.automation.github.context.BaseContextService;
import org.commonhaus.automation.github.context.GitHubTeamService;
import org.commonhaus.automation.github.hr.config.ConfigWatcher;
import org.commonhaus.automation.github.hr.config.HausRulesBotConfig;
import org.commonhaus.automation.github.hr.config.NoticeConfig;
import org.commonhaus.automation.github.hr.config.VoteConfig;

@Singleton
public class AppContextService extends BaseContextService {

    @Inject
    ConfigWatcher configWatcher;

    @Inject
    HausRulesBotConfig appConfig;

    @Inject
    GitHubTeamService teamMembershipService;

    public GitHubTeamService getTeamMembershipService() {
        return teamMembershipService;
    }

    public NoticeConfig getNoticeConfig(String repoFullName) {
        return configWatcher.getNoticeConfig(repoFullName);
    }

    public VoteConfig getVoteConfig(String repoFullName) {
        return configWatcher.getVoteConfig(repoFullName);
    }
}
