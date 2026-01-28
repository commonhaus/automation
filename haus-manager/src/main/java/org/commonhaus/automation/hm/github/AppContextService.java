package org.commonhaus.automation.hm.github;

import static org.commonhaus.automation.github.context.GitHubQueryContext.toFullName;

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

    public String getOrganization() {
        return mgrBotConfig.home().organization();
    }

    public ReportQueryContext getReportQueryContext(String repoFullName) {
        var fullName = repoFullName.contains("/")
                ? repoFullName
                : toFullName(getOrganization(), repoFullName);

        return new ReportQueryContext(this, this.tokenClients, fullName);
    }

    public ScopedQueryContext getHomeQueryContext() {
        return installationMap.getOrgScopedQueryContext(this, mgrBotConfig.home().repositoryFullName());
    }
}
