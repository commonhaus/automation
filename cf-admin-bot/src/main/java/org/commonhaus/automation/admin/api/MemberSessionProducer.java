package org.commonhaus.automation.admin.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Produces;

import org.commonhaus.automation.admin.github.AppContextService;

import io.quarkus.logging.Log;
import io.quarkus.oidc.UserInfo;
import io.quarkus.security.identity.SecurityIdentity;

@ApplicationScoped
public class MemberSessionProducer {
    @Inject
    AppContextService appCtx;

    @Inject
    UserInfo userInfo;

    @Inject
    SecurityIdentity identity;

    @Produces
    @RequestScoped
    public MemberSession getMemberSession() {
        MemberSession memberSession = MemberSession.getMemberSession(appCtx, userInfo, identity);
        Log.debugf("MemberSessionProducer.getMemberSession: %s", memberSession.nodeId());
        return memberSession;
    }
}
