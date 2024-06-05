package org.commonhaus.automation.admin.api;

import java.io.Serializable;

import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.admin.github.AppContextService;

import io.quarkus.oidc.UserInfo;
import io.quarkus.security.identity.SecurityIdentity;

@Interceptor
@KnownUser
public class KnownUserInterceptor implements Serializable {

    @Inject
    AppContextService appCtx;

    @Inject
    UserInfo userInfo;

    @Inject
    SecurityIdentity identity;

    @AroundInvoke
    public Object checkKnownUser(InvocationContext ctx) throws Exception {
        MemberSession session = MemberSession.getMemberProfile(appCtx, userInfo, identity);
        if (appCtx.userIsKnown(session)) {
            return ctx.proceed();
        }
        return Response.status(Response.Status.FORBIDDEN).build();
    }
}
