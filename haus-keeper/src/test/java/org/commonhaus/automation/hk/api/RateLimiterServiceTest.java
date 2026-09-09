package org.commonhaus.automation.hk.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.commonhaus.automation.hk.config.RateLimitConfig;
import org.junit.jupiter.api.Test;

public class RateLimiterServiceTest {

    static RateLimiterService newService(int memberLimit, int statusLimit, Duration window) {
        RateLimiterService service = new RateLimiterService();
        service.config = new RateLimitConfig() {
            @Override
            public BudgetConfig memberEndpoints() {
                return budget(memberLimit, window);
            }

            @Override
            public BudgetConfig statusEndpoint() {
                return budget(statusLimit, window);
            }
        };
        return service;
    }

    static RateLimitConfig.BudgetConfig budget(int limit, Duration window) {
        return new RateLimitConfig.BudgetConfig() {
            @Override
            public int limit() {
                return limit;
            }

            @Override
            public Duration window() {
                return window;
            }
        };
    }

    @Test
    void memberEndpointRespectsConfiguredLimit() {
        RateLimiterService service = newService(2, 1, Duration.ofMinutes(1));
        String nodeId = "node-" + System.nanoTime();

        assertThat(service.tryAcquireMemberEndpoint(nodeId)).isTrue();
        assertThat(service.tryAcquireMemberEndpoint(nodeId)).isTrue();
        assertThat(service.tryAcquireMemberEndpoint(nodeId)).isFalse();
    }

    @Test
    void statusEndpointRespectsOwnTighterLimit() {
        RateLimiterService service = newService(10, 1, Duration.ofMinutes(1));
        String nodeId = "node-" + System.nanoTime();

        assertThat(service.tryAcquireStatusEndpoint(nodeId)).isTrue();
        assertThat(service.tryAcquireStatusEndpoint(nodeId)).isFalse();
    }

    @Test
    void differentNodeIdsHaveIndependentBudgets() {
        RateLimiterService service = newService(1, 1, Duration.ofMinutes(1));
        String nodeA = "node-a-" + System.nanoTime();
        String nodeB = "node-b-" + System.nanoTime();

        assertThat(service.tryAcquireMemberEndpoint(nodeA)).isTrue();
        assertThat(service.tryAcquireMemberEndpoint(nodeA)).isFalse();
        assertThat(service.tryAcquireMemberEndpoint(nodeB)).isTrue();
    }

    @Test
    void memberAndStatusBudgetsAreIndependentForSameNodeId() {
        RateLimiterService service = newService(1, 1, Duration.ofMinutes(1));
        String nodeId = "node-" + System.nanoTime();

        assertThat(service.tryAcquireMemberEndpoint(nodeId)).isTrue();
        assertThat(service.tryAcquireMemberEndpoint(nodeId)).isFalse();

        // status budget for the same nodeId is unaffected by member-endpoint exhaustion
        assertThat(service.tryAcquireStatusEndpoint(nodeId)).isTrue();
    }
}
