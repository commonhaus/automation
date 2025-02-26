package org.commonhaus.automation.hk.api;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.hk.AdminDataCache;
import org.commonhaus.automation.hk.api.MemberApplicationProcess.ApplicationPost;
import org.commonhaus.automation.hk.data.ApiResponse.Type;
import org.commonhaus.automation.hk.data.CommonhausUser;
import org.commonhaus.automation.hk.data.MemberStatus;
import org.commonhaus.automation.hk.data.MembershipApplication;
import org.commonhaus.automation.hk.github.AppContextService;
import org.commonhaus.automation.hk.github.CommonhausDatastore;
import org.commonhaus.automation.hk.github.CommonhausDatastore.UpdateEvent;
import org.commonhaus.automation.hk.github.DatastoreQueryContext;
import org.kohsuke.github.GHMyself;

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
            CommonhausUser user = datastore.getCommonhausUser(session);
            if (user == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }

            MembershipApplication application = user.application();
            MemberApplicationProcess.MemberApplicationIssue applicationData = application == null
                    ? null
                    : memberApplicationProcess.findUserApplication(session, application.nodeId(), true);

            if (application == null && applicationData == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            } else if (applicationData == null || !applicationData.isValid() || user.status().updateToPending()) {
                return doUserApplicationUpdate(user, applicationData, null,
                        getNotificationEmail(session)); // WRITE
            }
            return user.toResponse()
                    .setData(Type.INFO, session.updateApplication(user))
                    .setData(Type.APPLY, applicationData)
                    .finish();
        } catch (Throwable e) {
            return ctx.toResponseWithEmail("getApplication",
                    "getApplication: Unable to retrieve application for " + session.login(), e);
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
            CommonhausUser user = datastore.getCommonhausUser(session);
            if (user == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }

            MembershipApplication application = user.application();
            MemberApplicationProcess.MemberApplicationIssue applicationData = application == null
                    ? null
                    : memberApplicationProcess.findUserApplication(session, application.nodeId(), false);

            if (applicationData != null && !applicationData.isValid()) {
                applicationData = null;
            }
            return doUserApplicationUpdate(user, applicationData, applicationPost,
                    getNotificationEmail(session)); // WRITE
        } catch (Throwable e) {
            return ctx.toResponseWithEmail("setApplication", "Unable to retrieve application for " + session.login(), e);
        }
    }

    private String getNotificationEmail(MemberSession session) {
        try {
            GHMyself myself = session.connection().getMyself();
            return myself.getEmails2().stream()
                    .filter(x -> x.isPrimary())
                    .map(x -> x.getEmail())
                    .findFirst().orElse(null);
        } catch (IOException e) {
            Log.errorf(e, "getNotificationEmail: Failed to get primary email for %s", session.login());
        }
        return null;
    }

    private Response doUserApplicationUpdate(CommonhausUser user,
            MemberApplicationProcess.MemberApplicationIssue applicationData,
            ApplicationPost post, String notificationEmail) {
        AtomicBoolean checkRunning = AdminDataCache.APPLICATION_CHECK.computeIfAbsent(session.login(),
                (k) -> new AtomicBoolean(false));

        if (checkRunning.compareAndSet(false, true)) {
            try {
                DatastoreQueryContext dqc = ctx.getDatastoreContext();

                boolean notFound = applicationData == null && post == null;
                boolean notOwner = applicationData != null && !applicationData.isValid();

                if (notFound || notOwner) {
                    // RESET/REMOVE APPLICATION FROM USER (missing or bad, no replacement)
                    String state = notFound ? "not found" : "not owner";
                    user = datastore.setCommonhausUser(new UpdateEvent(user,
                            (c, u) -> {
                                u.setApplication(null);
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
                                        u.setStatus(MemberStatus.PENDING);
                                    }
                                },
                                "Set status to PENDING",
                                false,
                                true));
                    }
                    return user.toResponse()
                            .setData(Type.INFO, session.updateApplication(user))
                            .setData(Type.APPLY, applicationData)
                            .finish();
                }

                // UPDATE APPLICATION ISSUE
                MemberApplicationProcess.MemberApplicationIssue updated = memberApplicationProcess.userUpdateApplicationIssue(
                        session, dqc,
                        applicationData, post, notificationEmail);

                if (dqc.hasErrors()) {
                    Throwable e = dqc.bundleExceptions();
                    dqc.clearErrors();
                    return ctx.toResponseWithEmail(dqc.getLogId(),
                            "doUserApplicationUpdate: Failed to update MembershipApplication issue", e);
                }
                if (updated == null) {
                    Log.errorf("doUserApplicationUpdate|%s: Updated data was not returned", session.login());
                    return Response.status(Response.Status.BAD_REQUEST).build();
                }
                final MembershipApplication application = updated.application;
                user = datastore.setCommonhausUser(new UpdateEvent(user,
                        (c, u) -> {
                            u.setApplication(application);
                            if (u.status().updateToPending()) {
                                u.setStatus(MemberStatus.PENDING);
                            }
                        },
                        "Updated membership application",
                        false,
                        true));

                return user.toResponse()
                        .setData(Type.APPLY, updated)
                        .setData(Type.INFO, session.updateApplication(user))
                        .finish();
            } finally {
                checkRunning.set(false);
            }
        } else {
            return Response.status(Response.Status.TOO_MANY_REQUESTS).build();
        }
    }

}
