package org.commonhaus.automation.hk.api;

import java.io.Serializable;

import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.hk.ActiveHausKeeperConfig;
import org.commonhaus.automation.hk.github.AppContextService;
import org.commonhaus.automation.hk.member.AccessRoleManager;

import io.quarkus.logging.Log;

@Interceptor
@KnownUser
public class KnownUserInterceptor implements Serializable {

    @Inject
    AppContextService appCtx;

    @Inject
    AccessRoleManager roleManager;

    @Inject
    protected ActiveHausKeeperConfig hkConfig;

    @Inject
    MemberSession session;

    @AroundInvoke
    public Object checkKnownUser(InvocationContext ctx) throws Exception {
        if (!hkConfig.isReady()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
        }
        try {
            if (session.userIsKnown(appCtx, roleManager)) {
                Log.debugf("[%s] Known User %s / %s: %s", session.login(), session.id(), session.nodeId(), session.roles());
                return ctx.proceed();
            }
            return Response.status(Response.Status.FORBIDDEN).build();
        } catch (Exception e) {
            if (e instanceof WebApplicationException) {
                return ((WebApplicationException) e).getResponse();
            }
            appCtx.logAndSendEmail("😎-known", "Exception checking for known user",
                    "Exception checking for user %s".formatted(session.login()), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }
}
