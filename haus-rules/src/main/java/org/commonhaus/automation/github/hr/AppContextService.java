package org.commonhaus.automation.github.hr;

import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.commonhaus.automation.github.context.BaseContextService;
import org.commonhaus.automation.github.context.GitHubTeamService;
import org.commonhaus.automation.github.hr.config.ConfigWatcher;
import org.commonhaus.automation.github.hr.config.NoticeConfig;
import org.commonhaus.automation.github.hr.config.VoteConfig;

import io.smallrye.config.ConfigMapping;

@Singleton
public class AppContextService extends BaseContextService {
    @ConfigMapping(prefix = "automation.voting")
    interface AppConfig {
        Optional<String> cron();
    }

    @Inject
    ConfigWatcher configWatcher;

    @Inject
    AppConfig appConfig;

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
