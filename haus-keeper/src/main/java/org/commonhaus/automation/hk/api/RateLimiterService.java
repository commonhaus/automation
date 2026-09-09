package org.commonhaus.automation.hk.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.commonhaus.automation.hk.AdminDataCache;
import org.commonhaus.automation.hk.config.RateLimitConfig;
import org.commonhaus.automation.hk.config.RateLimitConfig.BudgetConfig;

@ApplicationScoped
public class RateLimiterService {

    @Inject
    RateLimitConfig config;

    public boolean tryAcquireMemberEndpoint(String nodeId) {
        return tryAcquire(AdminDataCache.RATE_LIMIT_MEMBER_ENDPOINTS, nodeId, config.memberEndpoints());
    }

    public boolean tryAcquireStatusEndpoint(String nodeId) {
        return tryAcquire(AdminDataCache.RATE_LIMIT_STATUS_ENDPOINT, nodeId, config.statusEndpoint());
    }

    private boolean tryAcquire(AdminDataCache cache, String nodeId, BudgetConfig budget) {
        RequestWindow window = cache.computeIfAbsent(nodeId,
                k -> new RequestWindow(budget.limit(), budget.window().toMillis()));
        return window.tryAcquire(System.currentTimeMillis());
    }
}
