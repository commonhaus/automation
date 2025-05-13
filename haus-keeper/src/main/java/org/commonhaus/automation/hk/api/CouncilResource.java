package org.commonhaus.automation.hk.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.hk.ProjectAliasManager;
import org.commonhaus.automation.hk.UserLoginVerifier;
import org.commonhaus.automation.hk.council.AsyncCommonhausService;
import org.commonhaus.automation.queue.PeriodicUpdateQueue;

import io.quarkus.logging.Log;
import io.quarkus.security.Authenticated;

@Authenticated
@ApplicationScoped
@Path("/member/")
public class CouncilResource {

    @Inject
    MemberSession session;

    @Inject
    PeriodicUpdateQueue updateQueue;

    @Inject
    ProjectAliasManager projectAliasManager;

    @Inject
    UserLoginVerifier userLoginVerifier;

    @Inject
    AsyncCommonhausService asyncCommonhausService;

    @GET
    @KnownUser
    @Path("/keeper/projectAliases")
    public Response triggerProjectAliasUpdate() {
        Log.debugf("[%s] Trigger project alias refresh for council members", session.login());
        if (session.roles().contains("cfc")) {
            updateQueue.queueReconciliation("refreshProjectAliases-user",
                    () -> projectAliasManager.refreshProjectAliases(true));
        }
        return Response.noContent().build();
    }

    @GET
    @KnownUser
    @Path("/keeper/verifyLogins")
    public Response triggerVerifyLogins() {
        Log.debugf("[%s] Trigger user login verification", session.login());
        if (session.roles().contains("cfc")) {
            updateQueue.queueReconciliation("verifyAllUserLogins-user",
                    () -> userLoginVerifier.verifyAllUserLogins(true));
        }
        return Response.noContent().build();
    }

    @GET
    @KnownUser
    @Path("/manager/org")
    public Response triggerOrgUpdate() {
        asyncCommonhausService.triggerOrgUpdate(session);
        return Response.noContent().build();
    }

    @GET
    @KnownUser
    @Path("/manager/projects")
    public Response triggerProjectUpdate() {
        asyncCommonhausService.triggerProjectUpdate(session);
        return Response.noContent().build();
    }

    @GET
    @KnownUser
    @Path("/manager/sponsors")
    public Response triggerSponsorUpdate() {
        asyncCommonhausService.triggerSponsorUpdate(session);
        return Response.noContent().build();
    }

    @GET
    @KnownUser
    @Path("/rules/votes")
    public Response triggerVoteCount() {
        asyncCommonhausService.triggerVoteCount(session);
        return Response.noContent().build();
    }
}
