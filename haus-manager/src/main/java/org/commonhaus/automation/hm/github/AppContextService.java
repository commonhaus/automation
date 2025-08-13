package org.commonhaus.automation.hm.github;

import static org.commonhaus.automation.github.context.GitHubQueryContext.toRelativeName;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.commonhaus.automation.github.context.BaseContextService;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.hm.config.ManagerBotConfig;

import io.quarkiverse.githubapp.TokenGitHubClients;

@Singleton
public class AppContextService extends BaseContextService {

    @Inject
    TokenGitHubClients tokenClients;

    @Inject
    ManagerBotConfig mgrBotConfig;

    public ReportQueryContext getReportQueryContext(String repoFullName) {
        String org = getOrganization();
        return new ReportQueryContext(this, tokenClients, org, toRelativeName(org, repoFullName));
    }

    public String getOrganization() {
        return mgrBotConfig.home().organization();
    }

    public ScopedQueryContext getHomeQueryContext() {
        return installationMap.getOrgScopedQueryContext(this, mgrBotConfig.home().organization());
    }
}
