package org.commonhaus.automation.hk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;

import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.Test;

import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.oidc.UserInfo;
import io.quarkus.security.identity.SecurityIdentity;

public class RateLimitInterceptorTest {

    static RateLimitInterceptor newInterceptor(int limit) {
        RateLimiterService service = RateLimiterServiceTest.newService(limit, 0, Duration.ofMinutes(1));

        RateLimitInterceptor interceptor = new RateLimitInterceptor();
        interceptor.rateLimiterService = service;
        return interceptor;
    }

    /** Builds a real MemberSession via the same MemberSessionProducer path used at runtime. */
    static MemberSession newSession(String nodeId) {
        UserInfo userInfo = new UserInfo(
                "{\"id\":1,\"login\":\"test-user\",\"node_id\":\"" + nodeId + "\"}");

        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.getCredential(AccessTokenCredential.class))
                .thenReturn(new AccessTokenCredential("test-token"));

        MemberSessionProducer producer = new MemberSessionProducer();
        producer.userInfo = userInfo;
        producer.identity = identity;
        return producer.getMemberSession();
    }

    @Test
    void proceedsWhenWithinBudget() throws Exception {
        RateLimitInterceptor interceptor = newInterceptor(1);
        interceptor.session = newSession("node-" + System.nanoTime());

        InvocationContext ctx = mock(InvocationContext.class);
        when(ctx.proceed()).thenReturn("ok");

        Object result = interceptor.checkRateLimit(ctx);

        assertThat(result).isEqualTo("ok");
        verify(ctx).proceed();
    }

    @Test
    void rejectsWithBare429WhenBudgetExhausted() throws Exception {
        RateLimitInterceptor interceptor = newInterceptor(1);
        interceptor.session = newSession("node-" + System.nanoTime());

        InvocationContext ctx = mock(InvocationContext.class);
        when(ctx.proceed()).thenReturn("ok");

        // consume the single allotted request
        Object first = interceptor.checkRateLimit(ctx);
        assertThat(first).isEqualTo("ok");

        Object second = interceptor.checkRateLimit(ctx);

        assertThat(second).isInstanceOf(Response.class);
        try (Response response = (Response) second) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.TOO_MANY_REQUESTS.getStatusCode());
            assertThat(response.getEntity()).isNull();
        }

        // proceed() only called once (for the admitted first call)
        verify(ctx).proceed();
        verifyNoMoreInteractions(ctx);
    }
}
