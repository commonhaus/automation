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
            ApplicationData applicationData = ctx.getOpenApplication(session, user.data.applicationId);
            if (applicationData == null) {
                user.data.applicationId = null;
                user = datastore.setCommonhausUser(user, session.roles(), "Removed membership application (not found)");
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            if (!applicationData.isOwner()) {
                user.data.applicationId = null;
                user = datastore.setCommonhausUser(user, session.roles(), "Removed membership application (not owner)");
                return Response.status(Response.Status.FORBIDDEN).build();
            }
            return user.toResponse()
                    .setData(Type.APPLY, applicationData)
                    .finish();
        } catch (Exception e) {
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
            ApplicationData applicationData = ctx.updateApplication(session, user.data.applicationId, applicationPost);
            if (applicationData == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            if (!applicationData.isOwner()) {
                return Response.status(Response.Status.FORBIDDEN).build();
            }

            user.data.applicationId = applicationData.issueId;
            user = datastore.setCommonhausUser(user, session.roles(), "Created membership application");

            if (user.postConflict()) { // on conflict, user is reset with value from repo
                // retry once.
                user.data.applicationId = applicationData.issueId;
                user = datastore.setCommonhausUser(user, session.roles(), "Created membership application");
            }

            return user.toResponse()
                    .setData(Type.APPLY, applicationData)
                    .finish();
        } catch (Exception e) {
            if (Log.isDebugEnabled()) {
                e.printStackTrace();
            }
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }
}
