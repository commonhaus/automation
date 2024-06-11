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
import org.commonhaus.automation.admin.github.CommonhausDatastore.UpdateEvent;
import org.commonhaus.automation.admin.github.ScopedQueryContext;

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
    MemberApplicationProcess memberApplicationProcess;

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
                    : memberApplicationProcess.findUserApplication(session, application.nodeId(), true);

            if (application == null && applicationData == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            } else if (applicationData == null || !applicationData.isValid() || user.status().updateToPending()) {
                return doUserApplicationUpdate(user, applicationData, null); // WRITE
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
        try {
            if (applicationPost == null) {
                Log.errorf("setApplication|%s: No application data", session.login());
                return Response.status(Response.Status.BAD_REQUEST).build();
            }
            CommonhausUser user = datastore.getCommonhausUser(session, false, false);
            if (user == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            MembershipApplication application = user.application();
            ApplicationData applicationData = application == null
                    ? null
                    : memberApplicationProcess.findUserApplication(session, application.nodeId(), false);

            if (applicationData != null && !applicationData.isValid()) {
                applicationData = null;
            }
            return doUserApplicationUpdate(user, applicationData, applicationPost); // WRITE
        } catch (Throwable e) {
            Log.errorf(e, "getApplication: Unable to retrieve application for %s: %s", session.login(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    private Response doUserApplicationUpdate(CommonhausUser user, ApplicationData applicationData,
            ApplicationPost post) {
        AtomicBoolean checkRunning = AdminDataCache.APPLICATION_CHECK.computeIfAbsent(session.login(),
                (k) -> new AtomicBoolean(false));

        if (checkRunning.compareAndSet(false, true)) {
            try {
                ScopedQueryContext qc = ctx.getDatastoreContext();

                boolean notFound = applicationData == null && post == null;
                boolean notOwner = applicationData != null && !applicationData.isValid();

                if (notFound || notOwner) {
                    // RESET/REMOVE APPLICATION FROM USER (missing or bad, no replacement)
                    String state = notFound ? "not found" : "not owner";
                    user = datastore.setCommonhausUser(new UpdateEvent(user,
                            (c, u) -> {
                                u.application = null;
                            },
                            "Remove membership application (" + state + ")",
                            false,
                            true));

                    return Response.status(Response.Status.NOT_FOUND).build();
                } else if (post == null) {
                    // UPDATING MISMATCHED STATUS
                    if (user.status().updateToPending()) {
                        user = datastore.setCommonhausUser(new UpdateEvent(user,
                                (c, u) -> {
                                    if (u.status().updateToPending()) {
                                        u.status(MemberStatus.PENDING);
                                    }
                                },
                                "Set status to PENDING",
                                false,
                                true));
                    }
                    return user.toResponse()
                            .setData(Type.APPLY, applicationData)
                            .finish();
                }

                // UPDATE APPLICATION ISSUE
                ApplicationData updated = memberApplicationProcess.userUpdateApplicationIssue(session, qc, applicationData,
                        post);

                if (qc.hasErrors()) {
                    Throwable e = qc.bundleExceptions();
                    qc.clearErrors();
                    ctx.logAndSendEmail(qc.getLogId(), "Failed to update MembershipApplication issue", e, null);
                    return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
                }
                if (updated == null) {
                    Log.errorf("doUserApplicationUpdate|%s: Updated data was not returned", session.login());
                    return Response.status(Response.Status.BAD_REQUEST).build();
                }
                final MembershipApplication application = updated.application;
                user = datastore.setCommonhausUser(new UpdateEvent(user,
                        (c, u) -> {
                            u.application = application;
                            if (u.status().updateToPending()) {
                                u.status(MemberStatus.PENDING);
                            }
                        },
                        "Updated membership application",
                        false,
                        true));

                return user.toResponse()
                        .setData(Type.APPLY, updated)
                        .finish();
            } finally {
                checkRunning.set(false);
            }
        } else {
            return Response.status(Response.Status.TOO_MANY_REQUESTS).build();
        }
    }

}
