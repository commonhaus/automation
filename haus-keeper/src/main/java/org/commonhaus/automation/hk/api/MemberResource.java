package org.commonhaus.automation.hk.api;

import java.net.URI;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.hk.AdminDataCache;
import org.commonhaus.automation.hk.data.ApiResponse;
import org.commonhaus.automation.hk.data.CommonhausUser;
import org.commonhaus.automation.hk.forwardemail.ForwardEmailService;
import org.commonhaus.automation.hk.github.AppContextService;
import org.commonhaus.automation.hk.github.CommonhausDatastore;
import org.commonhaus.automation.hk.github.DatastoreEvent.UpdateEvent;
import org.commonhaus.automation.hk.member.AccessRoleManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.logging.Log;
import io.quarkus.security.Authenticated;

@Path("/member")
@Authenticated
@ApplicationScoped
public class MemberResource {

    @ConfigProperty(name = "quarkus.oidc.authentication.cookie-domain")
    String cookieDomain;

    @Inject
    AppContextService ctx;

    @Inject
    AccessRoleManager roleManager;

    @Inject
    CommonhausDatastore datastore;

    @Inject
    MemberSession session;

    @Inject
    ForwardEmailService emailService;

    @GET
    @Path("/github")
    @Produces("application/json")
    public Response githubLogin() {
        Log.debugf("[%s] /member/github %s, %s", session.nodeId(), session.login(), session.roles());
        // redirect to the member home page
        return Response.seeOther(URI.create("/member/login"))
                .build();
    }

    @GET
    @Path("/login")
    @Produces("application/json")
    public Response finishLogin() {
        Log.debugf("[%s] /member/login %s, %s", session.nodeId(), session.login(), session.roles());

        // redirect to the member home page
        return Response.seeOther(ctx.getMemberHome())
                .cookie(new NewCookie.Builder("id")
                        .value(session.nodeId())
                        .domain(cookieDomain)
                        .path("/")
                        .secure(true)
                        .maxAge(60)
                        .build())
                .build();
    }

    @GET
    @KnownUser
    @Path("/me")
    @Produces("application/json")
    public Response getUserInfo() {
        if (session.hasConnection()) {
            return Response.ok(new ApiResponse(ApiResponse.Type.INFO, session.getUserData())).build();
        } else {
            return ctx.toResponse("getUserInfo", "Unable to establish connection to GH for " + session.login(),
                    session.connectionError());
        }
    }

    @GET
    @KnownUser
    @Path("/commonhaus")
    @Produces("application/json")
    public Response getCommonhausUser() {
        try {
            CommonhausUser user = datastore.getCommonhausUser(session, false, true);
            if (user == null) {
                // This should not happen after a fetch with create=true
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
            }
            return user.toResponse()
                    .setData(ApiResponse.Type.INFO, session.updateApplication(user))
                    .finish();
        } catch (Exception e) {
            return ctx.toResponse("getCommonhausUser", "Unable to get user data for " + session.login(), e);
        }
    }

    @POST
    @KnownUser
    @Path("/commonhaus/status")
    @Produces("application/json")
    public Response updateUserStatus(@DefaultValue("false") @QueryParam("refresh") boolean refresh) {
        if (refresh) {
            // reset all the things.
            AdminDataCache.forgetUser(session);
            // re-fetch the user
            session.userIsKnown(ctx, roleManager);
            Log.debugf("[%s] REFRESH /member/commonhaus/status %s", session.login(), session.roles());
        }

        try {
            CommonhausUser user = datastore.getCommonhausUser(session, refresh, true);
            if (refresh) {
                emailService.forgetUser(session, user);
            }

            final Set<String> roles = session.roles();
            if (user.statusUpdateRequired(ctx, roles)) {
                // Refresh the user's status
                user = datastore.setCommonhausUser(new UpdateEvent(
                        user,
                        (c, u) -> u.updateMemberStatus(c, roles),
                        "Update member status",
                        false,
                        false));
            }
            return user.toResponse()
                    .setData(ApiResponse.Type.INFO, session.updateApplication(user))
                    .finish();
        } catch (Exception e) {
            return ctx.toResponse("updateUserStatus", "Unable to update status for " + session.login(), e);
        }
    }
}
