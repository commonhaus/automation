package org.commonhaus.automation.hk.member;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.github.context.DataCommonComment;
import org.commonhaus.automation.github.context.DataCommonItem;
import org.commonhaus.automation.github.context.DataLabel;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.github.context.GitHubTeamService;
import org.commonhaus.automation.hk.AdminDataCache;
import org.commonhaus.automation.hk.data.ApplicationIssue;
import org.commonhaus.automation.hk.data.CommonhausUser;
import org.commonhaus.automation.hk.data.MemberStatus;
import org.commonhaus.automation.hk.github.AppContextService;
import org.commonhaus.automation.hk.github.CommonhausDatastore;
import org.commonhaus.automation.hk.github.DatastoreEvent.UpdateEvent;
import org.commonhaus.automation.hk.github.DatastoreQueryContext;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHUser;

import io.quarkus.logging.Log;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;

@ApplicationScoped
public class MemberApplicationProcess {

    public static final String ACCEPTED = "application/accepted";
    public static final String DECLINED = "application/declined";
    public static final String NEW = "application/new";
    static final Pattern CONTRIBUTIONS = Pattern.compile(
            "([\\s\\S]*?<!--CONTRIBUTION::-->)([\\s\\S]*?)(<!--::CONTRIBUTION-->[\\s\\S]*?)", Pattern.CASE_INSENSITIVE);
    static final Pattern NOTES = Pattern.compile("([\\s\\S]*?<!--NOTES::-->)([\\s\\S]*?)(<!--::NOTES-->[\\s\\S]*?)",
            Pattern.CASE_INSENSITIVE);
    static final Pattern NOTIFICATION = Pattern.compile("<!-- notify::(\\S+?) -->");
    static final Pattern STRIP_COMMENTS = Pattern.compile("<!--:?:?(CONTRIBUTION|NOTES):?:?-->", Pattern.CASE_INSENSITIVE);

    @CheckedTemplate
    static class Templates {
        public static native TemplateInstance applicationAccepted();

        public static native TemplateInstance applicationDeclined();

        public static native TemplateInstance applicationUpdated(String body);
    }

    // Add a result class to encapsulate the operation outcome
    public static record MemberApplicationResult(
            Response.Status status,
            CommonhausUser user,
            MemberApplication data) {

        public boolean returnImmediately() {
            return status == Response.Status.TOO_MANY_REQUESTS;
        }
    }

    public record ApplicationPost(
            String contributions,
            String additionalNotes) {
    }

    @Inject
    AppContextService ctx;

    @Inject
    CommonhausDatastore datastore;

    @Inject
    GitHubTeamService teamService;

    private MemberApplicationState getApplicationState(MemberInfo memberInfo) {
        String entryKey = CommonhausDatastore.getKey(memberInfo.login(), memberInfo.id());
        return AdminDataCache.APPLICATION_STATE.computeIfAbsent(entryKey,
                k -> new MemberApplicationState(memberInfo.login(), memberInfo.id()));
    }

    private DatastoreQueryContext getDatastoreQueryContext(MemberInfo memberInfo) {
        return ctx.getDatastoreContext().withLogId(CommonhausDatastore.dataPath(memberInfo.id()));
    }

    /**
     * Called by REST API: GET /member/apply
     *
     * @param session MemberInfo for the current user
     * @param user CommonhausUser to retrieve application for
     * @return MemberApplicationResult to map to HTTP/JSON response
     * @throws Throwable for error response
     */
    public MemberApplicationResult getUserApplication(MemberInfo session, CommonhausUser user) {
        DatastoreQueryContext dqc = getDatastoreQueryContext(session);
        MemberApplicationState applicationState = getApplicationState(session);

        MemberApplicationResult result = findUserApplication(dqc, user, applicationState);
        throwIfErrors(dqc);
        if (result != null && result.returnImmediately()) {
            return result;
        }

        Response.Status status = result == null
                ? Response.Status.OK
                : result.status();

        if (applicationState.userUpdateRequired(user)) {
            // Update the user record to reflect the application state
            // (e.g. missed status update or missing application issue)
            user = updateUserRecord(dqc, user, applicationState, session);
            throwIfErrors(dqc);
        }

        DataCommonItem issue = applicationState.getIssue();
        MemberApplication application = applicationState.getApplication();
        if (issue != null && application.isValid()) {
            MemberApplication.Feedback feedback = getFeedback(dqc, issue.id, issue.mostRecentEdit());
            if (feedback != null) {
                application.setFeedback(feedback);
            }
            throwIfErrors(dqc);
        }
        return new MemberApplicationResult(status, user, application);
    }

    /**
     * Called by REST API: POST /member/apply
     *
     * @param session MemberInfo for the current user
     * @param user CommonhausUser to retrieve application for
     * @param post ApplicationPost data to update application with
     * @return MemberApplicationResult to map to HTTP/JSON response
     * @throws Throwable for error response
     */
    public MemberApplicationResult setUserApplication(MemberInfo session, CommonhausUser user, ApplicationPost post) {
        DatastoreQueryContext dqc = getDatastoreQueryContext(session);
        MemberApplicationState applicationState = getApplicationState(session);

        MemberApplicationResult result = updateApplication(dqc, user, applicationState, post, session);
        throwIfErrors(dqc);
        if (result != null && result.returnImmediately()) {
            return result;
        }

        Response.Status status = result == null
                ? Response.Status.OK
                : result.status();

        if (applicationState.userUpdateRequired(user)) {
            // Update the user record to reflect the application state
            user = updateUserRecord(dqc, user, applicationState, session);
            throwIfErrors(dqc);
        }

        return new MemberApplicationResult(status, user, applicationState.getApplication());
    }

    MemberApplicationResult findUserApplication(DatastoreQueryContext dqc, CommonhausUser user,
            MemberApplicationState applicationState) {

        DataCommonItem issue = applicationState.getIssue();
        ApplicationIssue appIssue = applicationState.getAppIssue(user);
        if (issue == null && appIssue != null) {
            issue = dqc.getItem(EventType.issue, appIssue.nodeId());
            if (dqc.hasErrors()) {
                return null;
            }
            if (issue == null) {
                Log.debugf("findUserApplication: issue %s not found for %s", appIssue.nodeId(), user.login());
                return new MemberApplicationResult(Response.Status.NOT_FOUND, user, null);
            }
            if (!applicationState.beginOperation()) {
                // another thread is already fetching the issue, (remote) caller should retry.
                return new MemberApplicationResult(Response.Status.TOO_MANY_REQUESTS, user, null);
            }
            try {
                // update the application state with the fetched or missing issue
                applicationState.refreshApplication(issue);
                if (!applicationState.isValid()) {
                    return new MemberApplicationResult(Response.Status.BAD_REQUEST, user, null);
                }
            } finally {
                // all done! release the lock.
                applicationState.releaseOperation();
            }
        }
        return null;
    }

    MemberApplicationResult updateApplication(DatastoreQueryContext dqc, CommonhausUser user,
            MemberApplicationState applicationState, ApplicationPost post, MemberInfo info) {

        String content = issueContent(info, post);
        Collection<DataLabel> labels = dqc.findLabels(List.of(NEW));

        if (!applicationState.beginOperation()) {
            // another thread is already fetching the issue, wait for it.
            return new MemberApplicationResult(Response.Status.TOO_MANY_REQUESTS, user, null);
        }
        try {
            DataCommonItem issue = applicationState.getIssue();
            if (issue == null) {
                issue = dqc.getItem(EventType.issue, applicationState.getIssueId());
                if (dqc.hasErrors()) {
                    return null;
                }
                // clear missing or invalid issue
                applicationState.refreshApplication(issue);
                if (!applicationState.isValid()) {
                    issue = null;
                }
            }

            // Create or update issue content
            issue = issue == null
                    ? dqc.createItem(EventType.issue,
                            createTitle(info),
                            content,
                            labels)
                    : dqc.updateItemDescription(EventType.issue,
                            issue.id, content, DataCommonItem.ISSUE_FIELDS);
            if (dqc.hasErrors()) {
                return null;
            }

            // refresh the application state with the updated issue
            applicationState.refreshApplication(issue);
            if (!applicationState.isValid()) {
                return new MemberApplicationResult(Response.Status.BAD_REQUEST, user, null);
            }
        } finally {
            // all done! release the lock.
            applicationState.releaseOperation();
        }
        return null;
    }

    /**
     * Call {@link CommonhausDatastore#setCommonhausUser(UpdateEvent)} to update the user
     * record to match the (current at that time) application state.
     * Handle creation of the application or eventual consistency if
     * something is missing or out of sync.
     *
     * The update to the user record will be done immediately in memory, but the
     * UpdateEvent will be queued and potentially replayed later when the record
     * is persisted. The update function must directly retrieve state from the
     * cache (last access) to ensure the update is correct.
     *
     * @param dqc
     * @param user
     * @param applicationState
     * @return
     */
    CommonhausUser updateUserRecord(DatastoreQueryContext dqc, CommonhausUser user,
            MemberApplicationState applicationState, final MemberInfo info) {
        try {
            return datastore.setCommonhausUser(new UpdateEvent(user,
                    (c, u) -> {
                        MemberApplicationState state = getApplicationState(info);
                        MemberApplication application = state.getApplication();
                        if (state.isValid()) {
                            // Make sure app issue is set (create path)
                            u.setAppIssue(application.appIssue);

                            // update to pending (create or missed update)
                            if (u.status().missedUpdateToPending()) {
                                u.setStatus(MemberStatus.PENDING);
                            }
                        } else {
                            // remove reference to missing/incorrect application issue
                            u.setAppIssue(null);
                        }
                    },
                    "resolve/reconcile membership application",
                    false,
                    true));
        } catch (Throwable e) {
            dqc.addException(e);
        }
        return user;
    }

    /**
     * GH REST API / WebHook event:
     * Handle an application event (issue label change) for a membership
     * application.
     *
     * Does not throw. Return DatastoreQueryContext dqc with errors.
     *
     * @param dqc QueryContext for the datastore repository
     * @param item The issue as Json-derived data
     * @param label The label that was added
     */
    public void handleApplicationLabelAdded(DatastoreQueryContext dqc, GHIssue issue, DataCommonItem item, DataLabel label) {
        if (!isAccepted(label) && !isDeclined(label)) {
            return;
        }

        String login = getLogin(item);
        GHUser applicant = dqc.getUser(login);
        String email = getNotificationEmail(item);
        MemberInfoAdapter memberInfo = new MemberInfoAdapter(applicant, email);

        String teamFullName = ctx.getTeamForRole(CommonhausUser.MEMBER_ROLE);
        if (teamFullName == null) {
            // should not happen, but in case something gets misaligned...
            dqc.addException(new IllegalStateException("No team found for role " + CommonhausUser.MEMBER_ROLE));
            return;
        }
        CommonhausUser user = datastore.getCommonhausUser(login, applicant.getId(), false, false);
        if (user == null) {
            // should not happen, but in case something gets misaligned...
            dqc.addException(new IllegalStateException("Label added to an application for an unknown user " + login));
            return;
        }
        MemberApplication applicationData = new MemberApplication(login, item);
        if (user.isMemberUndefined() && !applicationData.isValid()) {
            // We haven't approved/declined this member yet: we need a valid application
            dqc.addException(new IllegalStateException(
                    "Unable to find valid application data for login %s and issue %s (%s)"
                            .formatted(login, item.id, item.title)));
            return;
        }

        MemberApplicationState applicationState = getApplicationState(memberInfo);
        if (!applicationState.beginOperation()) {
            // another thread is already fetching the issue, wait for it.
            return;
        }
        try {
            applicationState.refreshApplication(item);
        } finally {
            applicationState.releaseOperation();
        }

        final boolean accepted = isAccepted(label);
        if (user.isMemberUndefined()) {
            datastore.setCommonhausUser(new UpdateEvent(user,
                    (c, u) -> {
                        if (u.status().updateFromPending()) {
                            u.setStatus(accepted ? MemberStatus.ACTIVE : MemberStatus.DECLINED);
                        }
                        u.setIsMember(accepted);
                        u.setAppIssue(null);
                    },
                    "Membership application " + (accepted ? "accepted" : "declined"),
                    true,
                    true));

            if (!dqc.hasErrors()) {
                if (accepted) {
                    // DIFFERENT CONTEXT: team organization
                    ctx.addTeamMember(applicant, teamFullName);
                }
                if (!dqc.hasErrors() && isValidEmail(email)) {
                    String body = accepted
                            ? Templates.applicationAccepted().render()
                            : Templates.applicationDeclined().render();
                    ctx.sendEmail(dqc.getLogId(),
                            "Commonhaus Foundation Membership Application",
                            body, new String[] { email });
                }
            }
        }

        if (dqc.hasErrors()) {
            return;
        }
        if (isValidEmail(email)) {
            // Try to remove the notification email from the issue body
            String updated = NOTIFICATION.matcher(item.body).replaceAll("");
            dqc.updateItemDescription(EventType.issue, item.id, updated, DataCommonItem.ISSUE_FIELDS);
        }
        dqc.closeIssue(issue);
        dqc.removeLabels(item.id, List.of(NEW));
    }

    /**
     * GH REST API / WebHook event:
     * Handle an application comment event.
     *
     * @param dqc QueryContext for the datastore repository
     * @param issue
     */
    public void handleApplicationComment(DatastoreQueryContext dqc, DataCommonItem issue, DataCommonComment comment) {
        if (isUserFeedback(comment.body)) {
            Log.debugf("[%s] updateApplicationComments: #%s - user feedback", dqc.getLogId(), issue.number);
            String notificationEmail = getNotificationEmail(issue);
            if (isValidEmail(notificationEmail)) {
                String body = Templates.applicationUpdated(comment.body.replaceAll("\\s*::response::\\s*", "")).render();
                ctx.sendEmail(dqc.getLogId(),
                        "Commonhaus Foundation Membership Application",
                        body, new String[] { notificationEmail });
            }
        }
    }

    MemberApplication.Feedback getFeedback(DatastoreQueryContext dqc, String nodeId, Instant mostRecentEdit) {
        List<DataCommonComment> comments = dqc.getComments(nodeId,
                x -> isUserFeedback(x.body) && isNewer(x, mostRecentEdit));

        return (comments == null || comments.isEmpty())
                ? null
                : new MemberApplication.Feedback(comments.get(0));
    }

    protected boolean isValidEmail(String notificationEmail) {
        return notificationEmail != null && !notificationEmail.isBlank();
    }

    public static String createTitle(MemberInfo session) {
        return "Membership application: %s (%s)".formatted(session.name(), session.login());
    }

    public static String getLogin(DataCommonItem issue) {
        return issue.title.replaceAll("Membership application: .*? \\((.*)\\)", "$1");
    }

    public static boolean isMemberApplicationEvent(DataCommonItem issue, DataLabel label) {
        return issue.title.startsWith("Membership application:")
                && !issue.closed
                && (label == null || ACCEPTED.equals(label.name) || DECLINED.equals(label.name));
    }

    public static String getNotificationEmail(DataCommonItem issue) {
        var matcher = NOTIFICATION.matcher(issue.body);
        return matcher.find() ? matcher.group(1) : null;
    }

    public static boolean isUserFeedback(String body) {
        return body.contains("::response::");
    }

    public static boolean isNew(DataLabel label) {
        return NEW.equals(label.name);
    }

    public static boolean isAccepted(DataLabel label) {
        return ACCEPTED.equals(label.name);
    }

    public static boolean isDeclined(DataLabel label) {
        return DECLINED.equals(label.name);
    }

    public static boolean isComplete(DataLabel label) {
        return isAccepted(label) || isDeclined(label);
    }

    public static boolean isNewer(DataCommonComment x, Instant issueMostRecent) {
        Log.debugf("isNewer: %s %s", x.mostRecentEdit(), issueMostRecent);
        return x.mostRecentEdit().isAfter(issueMostRecent);
    }

    public static String issueContent(MemberInfo session, ApplicationPost applicationPost) {
        return """
                [%s](%s)

                > [!TIP]
                > - Include "::response::" in your comment to send feedback to the applicant.
                > - Add the 'application/accepted' label to accept the application.
                > - Add the 'application/declined' label to decline or reject the application.

                ## Contribution Details
                <!--CONTRIBUTION::-->
                %s
                <!--::CONTRIBUTION-->

                ## Additional Notes
                <!--NOTES::-->
                %s
                <!--::NOTES-->
                %s
                <!--vote::marthas approve="+1" ok="eyes" revise="-1" threshold="twothirds"-->
                """.formatted(
                session.login(),
                session.url(),
                STRIP_COMMENTS.matcher(applicationPost.contributions()).replaceAll(" "),
                STRIP_COMMENTS.matcher(applicationPost.additionalNotes()).replaceAll(" "),
                session.notificationEmail().map(x -> "<!-- notify::%s -->".formatted(x)).orElse(""));
    }

    void throwIfErrors(DatastoreQueryContext dqc) {
        if (dqc.hasErrors()) {
            throw dqc.bundleExceptions();
        }
    }
}
