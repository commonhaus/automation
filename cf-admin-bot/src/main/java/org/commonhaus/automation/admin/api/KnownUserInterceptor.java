package org.commonhaus.automation.admin.api;

import java.io.Serializable;

import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.admin.github.AppContextService;

import io.quarkus.oidc.UserInfo;

@Interceptor
@KnownUser
public class KnownUserInterceptor implements Serializable {

    @Inject
    AppContextService appCtx;

    @Inject
    UserInfo userInfo;

    @AroundInvoke
    public Object checkKnownUser(InvocationContext ctx) throws Exception {
        if (!appCtx.userIsKnown(userInfo.getString("login"))) {
            return Response.status(Response.Status.METHOD_NOT_ALLOWED).build();
        }
        return ctx.proceed();
    }
}
