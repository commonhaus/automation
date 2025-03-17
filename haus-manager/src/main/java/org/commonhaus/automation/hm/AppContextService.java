package org.commonhaus.automation.hm;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.commonhaus.automation.github.context.BaseContextService;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.hm.config.ManagerBotConfig;

@Singleton
public class AppContextService extends BaseContextService {

    @Inject
    ManagerBotConfig mgrBotConfig;

    public ScopedQueryContext getDefaultQueryContext() {
        return installationMap.getOrgScopedQueryContext(this, mgrBotConfig.configOrganization());
    }
}
