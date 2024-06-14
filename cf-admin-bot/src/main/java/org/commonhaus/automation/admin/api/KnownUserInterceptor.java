package org.commonhaus.automation.admin.api;

import java.io.Serializable;

import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.admin.github.AppContextService;

import io.quarkus.logging.Log;

@Interceptor
@KnownUser
public class KnownUserInterceptor implements Serializable {

    @Inject
    AppContextService appCtx;

    @Inject
    MemberSession session;

    @AroundInvoke
    public Object checkKnownUser(InvocationContext ctx) throws Exception {
        if (session.userIsKnown(appCtx)) {
            Log.debugf("[%s] Known User %s / %s: %s", session.login(), session.id(), session.nodeId(), session.roles());

            return ctx.proceed();
        }
        return Response.status(Response.Status.FORBIDDEN).build();
    }
}
