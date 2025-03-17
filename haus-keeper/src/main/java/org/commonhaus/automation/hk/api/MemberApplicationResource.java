package org.commonhaus.automation.hk.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.hk.data.ApiResponse.Type;
import org.commonhaus.automation.hk.data.CommonhausUser;
import org.commonhaus.automation.hk.github.AppContextService;
import org.commonhaus.automation.hk.github.CommonhausDatastore;
import org.commonhaus.automation.hk.member.MemberApplicationProcess;
import org.commonhaus.automation.hk.member.MemberApplicationProcess.ApplicationPost;
import org.commonhaus.automation.hk.member.MemberApplicationProcess.MemberApplicationResult;

import io.quarkus.logging.Log;
import io.quarkus.security.Authenticated;

@Path("/member/apply")
@Authenticated
@ApplicationScoped
public class MemberApplicationResource {
    @Inject
    AppContextService ctx;

    @Inject
    CommonhausDatastore datastore;

    @Inject
    MemberSession session;

    @Inject
    MemberApplicationProcess applicationProcess;

    @GET
    @KnownUser
    @Produces("application/json")
    public Response getApplication() {
        try {
            CommonhausUser user = datastore.getCommonhausUser(session);
            if (user == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            var result = applicationProcess.getUserApplication(session, user);
            return createResponse(result);
        } catch (Throwable e) {
            return ctx.toResponse("getApplication",
                    "getApplication: Unable to retrieve application for " + session.login(), e);
        }
    }

    @POST
    @KnownUser
    @Produces("application/json")
    public Response setApplication(ApplicationPost applicationPost) {
        try {
            CommonhausUser user = datastore.getCommonhausUser(session);
            if (user == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            if (applicationPost == null) {
                Log.errorf("setApplication|%s: No application data", session.login());
                return Response.status(Response.Status.BAD_REQUEST).build();
            }
            var result = applicationProcess.setUserApplication(session, user, applicationPost);
            return createResponse(result);
        } catch (Throwable e) {
            return ctx.toResponse("setApplication", "Unable to update application for " + session.login(), e);
        }
    }

    private Response createResponse(MemberApplicationResult result) {
        return switch (result.status()) {
            case BAD_REQUEST -> Response.status(Response.Status.BAD_REQUEST).build();
            case NOT_FOUND -> Response.status(Response.Status.NOT_FOUND).build();
            default -> result.user().toResponse()
                    .responseStatus(result.status())
                    .setData(Type.APPLY, result.data())
                    .setData(Type.INFO, session.updateApplication(result.user()))
                    .finish();
        };
    }
}
