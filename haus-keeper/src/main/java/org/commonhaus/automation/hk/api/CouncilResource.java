package org.commonhaus.automation.hk.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.hk.ProjectAliasManager;
import org.commonhaus.automation.hk.UserLoginVerifier;
import org.commonhaus.automation.queue.PeriodicUpdateQueue;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;

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

    @RestClient
    HausManagerClient hausManagerClient;

    @RestClient
    HausRulesClient hausRulesClient;

    @GET
    @KnownUser
    @Path("/keeper/projectAliases")
    public Response triggerProjectAliasUpdate() {
        Log.debugf("[%s] Trigger project alias refresh for council members", session.login());
        if (session.roles().contains("cfc")) {
            projectAliasManager.refreshProjectAliases(true);
        }
        return Response.ok().build();
    }

    @GET
    @KnownUser
    @Path("/keeper/verifyLogins")
    public Response triggerVerifyLogins() {
        Log.debugf("[%s] Trigger user login verification", session.login());
        if (session.roles().contains("cfc")) {
            userLoginVerifier.verifyAllUserLogins(true);
        }
        return Response.ok().build();
    }

    @GET
    @KnownUser
    @Path("/manager/org")
    public Response triggerOrgUpdate() {
        Log.debugf("[%s] Trigger org update", session.login());
        if (session.roles().contains("egc")) {
            hausManagerClient.triggerOrgUpdate();
        }
        return Response.ok().build();
    }

    @GET
    @KnownUser
    @Path("/manager/projects")
    public Response triggerProjectUpdate() {
        Log.debugf("[%s] Trigger project update", session.login());
        if (session.roles().contains("cfc")) {
            hausManagerClient.triggerProjectUpdate();
        }
        return Response.ok().build();
    }

    @GET
    @KnownUser
    @Path("/manager/sponsors")
    public Response triggerSponsorUpdate() {
        Log.debugf("[%s] Trigger sponsor update", session.login());
        if (session.roles().contains("cfc")) {
            hausManagerClient.triggerSponsorUpdate();
        }
        return Response.ok().build();
    }

    @GET
    @KnownUser
    @Path("/rules/votes")
    public Response triggerVoteCount() {
        Log.debugf("[%s] Trigger vote counting", session.login());
        if (session.roles().contains("cfc")) {
            // trigger immediate team sync
            hausRulesClient.triggerVoteCount();
        }
        return Response.ok().build();
    }

    @RegisterRestClient(configKey = "haus-manager")
    public interface HausManagerClient {
        @GET
        @Path("/projects")
        Response triggerProjectUpdate();

        @GET
        @Path("/org")
        Response triggerOrgUpdate();

        @GET
        @Path("/sponsors")
        Response triggerSponsorUpdate();
    }

    @RegisterRestClient(configKey = "haus-rules")
    public interface HausRulesClient {
        @GET
        @Path("/votes")
        Response triggerVoteCount();
    }
}
