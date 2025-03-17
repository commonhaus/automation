package org.commonhaus.automation.hk.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import io.quarkus.logging.Log;
import io.quarkus.security.Authenticated;

@Authenticated
@ApplicationScoped
@Path("/member/council")
public class CouncilResource {

    public static record TeamSyncTriggerEvent() {
    }

    @Inject
    Event<TeamSyncTriggerEvent> teamSyncTriggerEvent;

    @Inject
    MemberSession session;

    @GET
    @KnownUser
    @Path("/teams")
    public Response triggerTeamSync() {
        if (session.roles().contains("cfc")) {
            // trigger immediate team sync
            Log.debugf("[%s] Triggering team sync for council members", session.login());
            teamSyncTriggerEvent.fire(new TeamSyncTriggerEvent());
        }
        return Response.ok().build();
    }
}
