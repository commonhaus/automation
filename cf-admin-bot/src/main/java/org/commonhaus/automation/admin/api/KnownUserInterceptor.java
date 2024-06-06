package org.commonhaus.automation.admin.api;

import java.io.Serializable;

import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.admin.github.AppContextService;

@Interceptor
@KnownUser
public class KnownUserInterceptor implements Serializable {

    @Inject
    AppContextService appCtx;

    @Inject
    MemberSession session;

    @AroundInvoke
    public Object checkKnownUser(InvocationContext ctx) throws Exception {
        if (appCtx.userIsKnown(session)) {
            return ctx.proceed();
        }
        return Response.status(Response.Status.FORBIDDEN).build();
    }
}
