package org.commonhaus.automation.hk.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.Test;

/**
 * Verifies Budget B's inline rate-limit check in MemberResource.updateUserStatus.
 * Only exercises the rejected path -- an admitted call falls through to
 * datastore/emailService/roleManager work that would need a full CDI graph to
 * test meaningfully; the admit/reject counting itself (limit, rolling window,
 * per-nodeId and per-budget independence) is already covered directly against
 * RateLimiterService by RateLimiterServiceTest. This file's unique job is
 * proving MemberResource's wiring: the check runs before any other work
 * (the refresh=true branch, since it does more) and rejects with a bare 429.
 */
public class MemberResourceRateLimitTest {

    /**
     * Budget already exhausted (limit 0): resource.datastore/emailService/roleManager/ctx
     * are left null on purpose -- if the rejected path touched any of them, these tests
     * would fail with a NullPointerException instead of observing a 429.
     */
    static MemberResource newExhaustedResource(String nodeId) {
        MemberResource resource = new MemberResource();
        resource.rateLimiterService = RateLimiterServiceTest.newService(0, 0, Duration.ofMinutes(1));
        resource.session = RateLimitInterceptorTest.newSession(nodeId);
        return resource;
    }

    @Test
    void rejectedCallReturnsBare429BeforeTouchingRefreshBranchWork() {
        // The check is the first line of updateUserStatus, before the refresh branch --
        // refresh=true here would normally hit AdminDataCache.forgetUser, session.userIsKnown,
        // and emailService.forgetUser before returning; none of that is wired up in this
        // resource, so reaching it would NPE instead of returning 429 cleanly.
        MemberResource resource = newExhaustedResource("node-" + System.nanoTime());

        try (Response response = resource.updateUserStatus(true)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.TOO_MANY_REQUESTS.getStatusCode());
            assertThat(response.getEntity()).isNull();
            assertThat(response.getHeaderString("Retry-After")).isNull();
        }
    }
}
