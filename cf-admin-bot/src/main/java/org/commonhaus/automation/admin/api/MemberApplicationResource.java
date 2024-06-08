package org.commonhaus.automation.admin.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.admin.api.ApiResponse.Type;
import org.commonhaus.automation.admin.api.ApplicationData.ApplicationPost;
import org.commonhaus.automation.admin.api.CommonhausUser.MemberStatus;
import org.commonhaus.automation.admin.api.CommonhausUser.MembershipApplication;
import org.commonhaus.automation.admin.github.AppContextService;
import org.commonhaus.automation.admin.github.CommonhausDatastore;

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

    @GET
    @KnownUser
    @Produces("application/json")
    public Response getApplication() {
        try {
            CommonhausUser user = datastore.getCommonhausUser(session, false);
            MembershipApplication application = user.application();
            if (application == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            ApplicationData applicationData = ctx.getOpenApplication(session, application.nodeId());
            if (applicationData == null) {
                user.application = null;
                user = datastore.setCommonhausUser(user, session.roles(),
                        "Remove membership application (not found)", false);
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            if (!applicationData.isOwner()) {
                user.application = null;
                user = datastore.setCommonhausUser(user, session.roles(),
                        "Remove membership application (not owner)", false);
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            if (user.status().updateToPending()) {
                // compensate for missing status
                user.status(MemberStatus.PENDING);
                user = datastore.setCommonhausUser(user, session.roles(), "Set status to PENDING", true);
            }
            return user.toResponse()
                    .setData(Type.APPLY, applicationData)
                    .finish();
        } catch (Throwable e) {
            if (Log.isDebugEnabled()) {
                e.printStackTrace();
            }
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @POST
    @KnownUser
    @Produces("application/json")
    public Response setApplication(ApplicationPost applicationPost) {
        try {
            CommonhausUser user = datastore.getCommonhausUser(session, false);
            ApplicationData applicationData = ctx.updateApplication(session, user, applicationPost);
            if (applicationData == null) {
                return Response.status(Response.Status.BAD_REQUEST).build();
            }

            user.application = applicationData.application;
            if (user.status().updateToPending()) {
                user.status(MemberStatus.PENDING);
            }
            user = datastore.setCommonhausUser(user, session.roles(), "Created membership application", false);

            if (user.postConflict()) { // on conflict, user is reset with value from repo
                // retry once.
                user.application = applicationData.application;
                user = datastore.setCommonhausUser(user, session.roles(), "Created membership application", false);
            }

            return user.toResponse()
                    .setData(Type.APPLY, applicationData)
                    .finish();
        } catch (Throwable e) {
            if (Log.isDebugEnabled()) {
                e.printStackTrace();
            }
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }
}
