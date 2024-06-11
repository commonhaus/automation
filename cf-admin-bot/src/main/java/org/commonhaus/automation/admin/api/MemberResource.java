package org.commonhaus.automation.admin.api;

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

import org.commonhaus.automation.admin.AdminDataCache;
import org.commonhaus.automation.admin.github.AppContextService;
import org.commonhaus.automation.admin.github.CommonhausDatastore;
import org.commonhaus.automation.admin.github.CommonhausDatastore.UpdateEvent;
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
    CommonhausDatastore datastore;

    @Inject
    MemberSession session;

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
    public Response getUserInfo(@DefaultValue("false") @QueryParam("refresh") boolean refresh) {
        if (refresh) {
            AdminDataCache.KNOWN_USER.invalidate(session.login());
            session.userIsKnown(ctx);
        }

        if (session.hasConnection()) {
            return Response.ok(new ApiResponse(ApiResponse.Type.INFO, session.getUserData())).build();
        } else {
            Log.errorf("getUserInfo: Unable to establish connection to GH for %s", session.login());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GET
    @KnownUser
    @Path("/commonhaus")
    @Produces("application/json")
    public Response getCommonhausUser(@DefaultValue("false") @QueryParam("refresh") boolean refresh) {
        if (refresh) {
            AdminDataCache.COMMONHAUS_DATA.invalidate(CommonhausDatastore.getKey(session));
        }

        try {
            CommonhausUser user = datastore.getCommonhausUser(session, refresh, true);
            if (user == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            return user.toResponse().finish();
        } catch (Exception e) {
            Log.errorf(e, "getCommonhausUser: Unable to get user data for %s: %s", session.login(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @POST
    @KnownUser
    @Path("/commonhaus/status")
    @Produces("application/json")
    public Response updateUserStatus() {
        try {
            CommonhausUser user = datastore.getCommonhausUser(session, false, false);
            final Set<String> roles = session.roles();
            if (user.updateMemberStatus(ctx, roles)) {
                // Refresh the user's status
                user = datastore.setCommonhausUser(new UpdateEvent(
                        user,
                        (c, u) -> u.updateMemberStatus(c, roles),
                        "Update member status",
                        false,
                        false));
            }
            return user.toResponse().finish();
        } catch (Exception e) {
            Log.errorf(e, "updateUserStatus: Unable to update status for %s: %s", session.login(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }
}
