package org.commonhaus.automation.admin.api;

import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.admin.AdminDataCache;
import org.commonhaus.automation.admin.api.ApiResponse.Type;
import org.commonhaus.automation.admin.api.ApplicationData.ApplicationPost;
import org.commonhaus.automation.admin.api.CommonhausUser.MemberStatus;
import org.commonhaus.automation.admin.api.CommonhausUser.MembershipApplication;
import org.commonhaus.automation.admin.github.AppContextService;
import org.commonhaus.automation.admin.github.CommonhausDatastore;

import io.quarkus.logging.Log;
import io.quarkus.security.Authenticated;
import io.smallrye.common.annotation.Blocking;

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
            CommonhausUser user = datastore.getCommonhausUser(session, false, false);
            if (user == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            MembershipApplication application = user.application();
            ApplicationData applicationData = application == null
                    ? null
                    : ctx.getOpenApplication(session, application.nodeId());

            if (applicationData == null || !applicationData.isOwner()) {
                if (application != null) {
                    user.application = null;
                    String state = applicationData == null ? "not found" : "not owner";
                    updateStatus(user, "Remove membership application (" + state + ")", false);
                }
                return Response.status(Response.Status.NOT_FOUND).build();
            }

            if (user.status().updateToPending()) {
                // compensate for missing status
                user.status(MemberStatus.PENDING);
                user = updateStatus(user, "Set status to PENDING", true);
            }
            return user.toResponse()
                    .setData(Type.APPLY, applicationData)
                    .finish();
        } catch (Throwable e) {
            Log.errorf(e, "getApplication: Unable to retrieve application for %s: %s", session.login(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @POST
    @KnownUser
    @Produces("application/json")
    public Response setApplication(ApplicationPost applicationPost) {
        AtomicBoolean checkRunning = AdminDataCache.APPLICATION_CHECK.computeIfAbsent(session.login(),
                (k) -> new AtomicBoolean(false));

        // There are a few separate updates here (dealing with the issue AND (separately) with the user record).
        // We'll enforce one at a time coming from the UI for this one.
        return checkRunning.compareAndSet(false, true)
                ? doApplicationUpdate(applicationPost, checkRunning)
                : Response.status(Response.Status.TOO_MANY_REQUESTS).build();
    }

    @Blocking
    private CommonhausUser updateStatus(CommonhausUser user, String message, boolean history) {
        AtomicBoolean checkRunning = AdminDataCache.APPLICATION_CHECK.computeIfAbsent(session.login(),
                (k) -> new AtomicBoolean(false));
        boolean iAmWriter = checkRunning.compareAndSet(false, true);
        if (iAmWriter) {
            try {
                return datastore.setCommonhausUser(user, session.roles(), message, history);
            } finally {
                checkRunning.set(false);
            }
        }
        return user;
    }

    @Blocking
    private Response doApplicationUpdate(ApplicationPost applicationPost, AtomicBoolean checkRunning) {
        try {
            CommonhausUser user = datastore.getCommonhausUser(session, false, false);
            if (user == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
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
            Log.errorf(e, "doApplicationUpdate: Unable to update user application for %s: %s", session.login(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        } finally {
            checkRunning.set(false);
        }
    }
}
