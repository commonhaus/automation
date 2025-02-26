package org.commonhaus.automation.hk.api;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.commonhaus.automation.github.context.DataCommonComment;
import org.commonhaus.automation.github.context.DataCommonItem;
import org.commonhaus.automation.github.context.DataLabel;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.hk.data.CommonhausUser;
import org.commonhaus.automation.hk.data.MemberStatus;
import org.commonhaus.automation.hk.data.MembershipApplication;
import org.commonhaus.automation.hk.github.AppContextService;
import org.commonhaus.automation.hk.github.CommonhausDatastore;
import org.commonhaus.automation.hk.github.CommonhausDatastore.UpdateEvent;
import org.commonhaus.automation.hk.github.DatastoreQueryContext;
import org.commonhaus.automation.markdown.MarkdownConverter;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHUser;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.quarkus.logging.Log;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.runtime.annotations.RegisterForReflection;

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

    @Inject
    AppContextService ctx;

    @Inject
    CommonhausDatastore datastore;

    /**
     * Handle an application comment event.
     *
     * @param dqc QueryContext for the datastore repository
     * @param issue
     */
    public void handleApplicationComment(DatastoreQueryContext dqc, DataCommonItem issue, DataCommonComment comment) {
        if (isUserFeedback(comment.body)) {
            Log.debugf("[%s] updateApplicationComments: #%s - user feedback", dqc.getLogId(), issue.number);
            String notificationEmail = getNotificationEmail(issue);
            if (isValid(notificationEmail)) {
                String body = Templates.applicationUpdated(comment.body.replaceAll("\\s*::response::\\s*", "")).render();
                ctx.sendEmail(dqc.getLogId(),
                        "Commonhaus Foundation Membership Application",
                        body, new String[] { notificationEmail });
            }
        } else {
            Log.debugf("[%s] updateApplicationComments: #%s - not user feedback", dqc.getLogId(), issue.number);
        }
    }

    /**
     * Handle an application event (issue label change) for a membership
     * application.
     *
     * @param dqc QueryContext for the datastore repository
     * @param item The issue as Json-derived data
     * @param label The label that was added
     * @throws Throwable
     */
    public void handleApplicationLabelAdded(DatastoreQueryContext dqc, GHIssue issue, DataCommonItem item, DataLabel label)
            throws Throwable {
        String login = getLogin(item);
        GHUser applicant = dqc.getUser(login);

        String teamFullName = ctx.getTeamForRole(CommonhausUser.MEMBER_ROLE);
        if (teamFullName == null) {
            // should not happen, but in case something gets misaligned...
            throw new IllegalStateException("No team found for role " + CommonhausUser.MEMBER_ROLE);
        }

        CommonhausUser user = datastore.getCommonhausUser(login, applicant.getId(), false, false);
        if (user == null) {
            // should not happen, but in case something gets misaligned...
            throw new IllegalStateException("Label added to an application for an unknown user " + login);
        }

        MemberApplicationIssue applicationData = new MemberApplicationIssue(login, item);
        // We haven't approved/declined this member yet: we need a valid application
        if (user.isMemberUndefined() && !applicationData.isValid()) {
            throw new IllegalStateException(
                    "Unable to find valid application data for login %s and issue %s (%s)"
                            .formatted(login, item.id, item.title));
        }

        String notificationEmail = getNotificationEmail(item);
        if (isAccepted(label)) {
            if (user.isMemberUndefined()) {
                datastore.setCommonhausUser(new UpdateEvent(user,
                        (c, u) -> {
                            if (u.status().updateFromPending()) {
                                u.setStatus(MemberStatus.ACTIVE);
                            }
                            u.setIsMember(true);
                            u.setApplication(null);
                        },
                        "Membership application accepted",
                        true,
                        true));
            }
            // (re-)try adding the user to the team if the above went ok.
            if (!dqc.hasErrors()
                    && ctx.addTeamMember(applicant, teamFullName)
                    && isValid(notificationEmail)) {
                String body = Templates.applicationAccepted().render();
                ctx.sendEmail(dqc.getLogId(),
                        "Welcome to the Commonhaus Foundation!",
                        body, new String[] { notificationEmail });
            }
        } else if (user.isMemberUndefined() && isDeclined(label)) {
            datastore.setCommonhausUser(new UpdateEvent(user,
                    (c, u) -> {
                        if (u.status().updateFromPending()) {
                            u.setStatus(MemberStatus.DECLINED);
                            u.setApplication(null);
                        }
                        u.setIsMember(false);
                    },
                    "Membership application declined",
                    true,
                    true));

            if (!dqc.hasErrors() && isValid(notificationEmail)) {
                String body = Templates.applicationDeclined().render();
                ctx.sendEmail(dqc.getLogId(),
                        "Commonhaus Foundation Membership Application",
                        body, new String[] { notificationEmail });
            }
        }

        if (dqc.hasErrors()) {
            throw dqc.bundleExceptions();
        }

        if (isValid(notificationEmail)) {
            // Try to remove the notification email from the issue body
            String updated = NOTIFICATION.matcher(item.body).replaceAll("");
            dqc.updateItemDescription(EventType.issue, item.id, updated, DataCommonItem.ISSUE_FIELDS);
        }
        dqc.closeIssue(issue);
        dqc.removeLabels(item.id, List.of(NEW));
    }

    protected boolean isValid(String notificationEmail) {
        return notificationEmail != null && !notificationEmail.isBlank();
    }

    /**
     * User action called when a user submits or updates their membership
     * application
     *
     * @param session User session
     * @param dqc QueryContext for the datastore repository
     * @param applicationData Current application data
     * @param applicationPost Application data from the user
     * @return updated ApplicationData object or null on error (see QueryContext for
     *         errors)
     */
    public MemberApplicationIssue userUpdateApplicationIssue(
            MemberSession session,
            DatastoreQueryContext dqc,
            MemberApplicationIssue applicationData,
            ApplicationPost applicationPost,
            String notificationEmail) {

        String content = issueContent(session, applicationPost, notificationEmail);
        Collection<DataLabel> labels = dqc.findLabels(List.of(NEW));
        MembershipApplication application = applicationData == null ? null : applicationData.application;

        DataCommonItem item = application == null
                ? dqc.createItem(EventType.issue,
                        createTitle(session),
                        content,
                        labels)
                : dqc.updateItemDescription(EventType.issue, application.nodeId(), content, DataCommonItem.ISSUE_FIELDS);

        return item == null
                ? null
                : new MemberApplicationIssue(session.login(), item);
    }

    MemberApplicationIssue findUserApplication(MemberSession session, String applicationId, boolean withComments)
            throws Throwable {
        DatastoreQueryContext dqc = ctx.getDatastoreContext();
        DataCommonItem issue = dqc.getItem(EventType.issue, applicationId);
        if (dqc.hasErrors()) {
            throw dqc.bundleExceptions();
        }
        MemberApplicationIssue application = new MemberApplicationIssue(session.login(), issue);
        if (application.isValid() && withComments) {
            Feedback feedback = getFeedback(dqc, applicationId, issue.mostRecentEdit());
            if (feedback != null) {
                application.setFeedback(feedback);
            }
        }
        return application;
    }

    Feedback getFeedback(DatastoreQueryContext dqc, String nodeId, Date mostRecentEdit) {
        List<DataCommonComment> comments = dqc.getComments(nodeId,
                x -> isUserFeedback(x.body) && isNewer(x, mostRecentEdit));

        return (comments == null || comments.isEmpty())
                ? null
                : new Feedback(comments.get(0));
    }

    public static String createTitle(MemberSession session) {
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

    public static boolean isNewer(DataCommonComment x, Date issueMostRecent) {
        Log.debugf("isNewer: %s %s", x.mostRecentEdit(), issueMostRecent);
        return x.mostRecentEdit().after(issueMostRecent);
    }

    public static String issueContent(MemberSession session, ApplicationPost applicationPost, String notificationEmail) {
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
                notificationEmail == null ? "" : "<!-- notify::%s -->".formatted(notificationEmail));
    }

    public record ApplicationPost(
            String contributions,
            String additionalNotes) {
    }

    public static class Feedback {
        final String htmlContent;
        final String date;

        public Feedback(DataCommonComment dataCommonComment) {
            String content = dataCommonComment.body.replaceAll("::response::", "").trim();

            this.htmlContent = MarkdownConverter.toHtml(content);
            date = dataCommonComment.mostRecentEdit().toString();
        }
    }

    @RegisterForReflection
    public static class MemberApplicationIssue {

        transient String title;
        transient MembershipApplication application;

        String created;
        String updated;
        String contributions;
        String additionalNotes;
        Feedback feedback;

        public MemberApplicationIssue(String login, DataCommonItem issue) {
            this.title = issue == null ? null : issue.title;
            if (title == null || !ownerEquals(login)) {
                return;
            }
            application = MembershipApplication.fromDataCommonType(issue);
            this.created = issue.createdAt.toString();

            if (issue.lastEditedAt != null && !issue.lastEditedAt.equals(issue.createdAt)) {
                this.updated = issue.lastEditedAt.toString();
            }
            this.contributions = extract(CONTRIBUTIONS, issue.body);
            this.additionalNotes = extract(NOTES, issue.body);
        }

        private String extract(Pattern pattern, String body) {
            var matcher = pattern.matcher(body);
            if (matcher.find()) {
                return matcher.group(2).trim();
            }
            return "";
        }

        public void setFeedback(Feedback feedback) {
            this.feedback = feedback;
        }

        @JsonIgnore
        public boolean isValid() {
            return application != null;
        }

        public boolean ownerEquals(String login) {
            return title != null && title.contains("(%s)".formatted(login));
        }

    }
}
