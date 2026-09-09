package org.commonhaus.automation.hk.api;

import java.io.Serializable;

import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.core.Response;

@Interceptor
@RateLimited
public class RateLimitInterceptor implements Serializable {

    @Inject
    MemberSession session;

    @Inject
    RateLimiterService rateLimiterService;

    @AroundInvoke
    public Object checkRateLimit(InvocationContext ctx) throws Exception {
        if (rateLimiterService.tryAcquireMemberEndpoint(session.nodeId())) {
            return ctx.proceed();
        }
        return Response.status(Response.Status.TOO_MANY_REQUESTS).build();
    }
}
