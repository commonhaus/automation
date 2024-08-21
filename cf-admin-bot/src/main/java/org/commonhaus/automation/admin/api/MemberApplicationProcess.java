package org.commonhaus.automation.admin.api;

import java.util.Collection;
import java.util.Date;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.commonhaus.automation.admin.api.CommonhausUser.MembershipApplication;
import org.commonhaus.automation.admin.api.MembershipApplicationData.ApplicationPost;
import org.commonhaus.automation.admin.api.MembershipApplicationData.Feedback;
import org.commonhaus.automation.admin.github.AppContextService;
import org.commonhaus.automation.admin.github.CommonhausDatastore;
import org.commonhaus.automation.admin.github.CommonhausDatastore.UpdateEvent;
import org.commonhaus.automation.admin.github.DatastoreQueryContext;
import org.commonhaus.automation.github.context.DataCommonComment;
import org.commonhaus.automation.github.context.DataCommonItem;
import org.commonhaus.automation.github.context.DataLabel;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.markdown.MarkdownConverter;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHUser;

import io.quarkus.logging.Log;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;

@ApplicationScoped
public class MemberApplicationProcess {

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
     * @param item
     */
    public void handleApplicationComment(DatastoreQueryContext dqc, DataCommonItem issue, DataCommonComment comment) {
        if (MembershipApplicationData.isUserFeedback(comment.body)) {
            Log.debugf("[%s] updateApplicationComments: #%s - user feedback", dqc.getLogId(), issue.number);
            String notificationEmail = MembershipApplicationData.getNotificationEmail(issue);
            if (isValid(notificationEmail)) {
                String body = Templates.applicationUpdated(comment.body.replaceAll("\\s*::response::\\s*", "")).render();
                String htmlBody = MarkdownConverter.toHtml(body);
                ctx.sendEmail(dqc.getLogId(),
                        "Commonhaus Foundation Membership Application",
                        body, htmlBody, new String[] { notificationEmail });
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
        String login = MembershipApplicationData.getLogin(item);
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

        MembershipApplicationData applicationData = new MembershipApplicationData(login, item);
        // We haven't approved/declined this member yet: we need a valid application
        if (user.emptyMember() && !applicationData.isValid()) {
            throw new IllegalStateException(
                    "Unable to find valid application data for login %s and issue %s (%s)"
                            .formatted(login, item.id, item.title));
        }

        String notificationEmail = MembershipApplicationData.getNotificationEmail(item);
        if (MembershipApplicationData.isAccepted(label)) {
            if (user.emptyMember()) {
                datastore.setCommonhausUser(new UpdateEvent(user,
                        (c, u) -> {
                            if (u.status().updateFromPending()) {
                                u.status(MemberStatus.ACTIVE);
                            }
                            u.isMember = true;
                            u.application = null;
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
                String htmlBody = MarkdownConverter.toHtml(body);
                ctx.sendEmail(dqc.getLogId(),
                        "Welcome to the Commonhaus Foundation!",
                        body, htmlBody, new String[] { notificationEmail });
            }
        } else if (user.isMember == null && MembershipApplicationData.isDeclined(label)) {
            datastore.setCommonhausUser(new UpdateEvent(user,
                    (c, u) -> {
                        if (u.status().updateFromPending()) {
                            u.status(MemberStatus.DECLINED);
                            u.application = null;
                        }
                        u.isMember = false;
                    },
                    "Membership application declined",
                    true,
                    true));

            if (!dqc.hasErrors() && isValid(notificationEmail)) {
                String body = Templates.applicationDeclined().render();
                String htmlBody = MarkdownConverter.toHtml(body);
                ctx.sendEmail(dqc.getLogId(),
                        "Commonhaus Foundation Membership Application",
                        body, htmlBody, new String[] { notificationEmail });
            }
        }

        if (dqc.hasErrors()) {
            throw dqc.bundleExceptions();
        }

        if (isValid(notificationEmail)) {
            // Try to remove the notification email from the issue body
            String updated = MembershipApplicationData.NOTIFICATION.matcher(item.body).replaceAll("");
            dqc.updateItemDescription(EventType.issue, item.id, updated, DataCommonItem.ISSUE_FIELDS);
        }
        dqc.closeIssue(issue);
        dqc.removeLabels(item.id, List.of(MembershipApplicationData.NEW));
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
    public MembershipApplicationData userUpdateApplicationIssue(
            MemberSession session,
            DatastoreQueryContext dqc,
            MembershipApplicationData applicationData,
            ApplicationPost applicationPost,
            String notificationEmail) {

        String content = MembershipApplicationData.issueContent(session, applicationPost, notificationEmail);
        Collection<DataLabel> labels = dqc.findLabels(List.of(MembershipApplicationData.NEW));
        MembershipApplication application = applicationData == null ? null : applicationData.application;

        DataCommonItem item = application == null
                ? dqc.createItem(EventType.issue,
                        MembershipApplicationData.createTitle(session),
                        content,
                        labels)
                : dqc.updateItemDescription(EventType.issue, application.nodeId(), content, DataCommonItem.ISSUE_FIELDS);

        return item == null
                ? null
                : new MembershipApplicationData(session.login(), item);
    }

    MembershipApplicationData findUserApplication(MemberSession session, String applicationId, boolean withComments)
            throws Throwable {
        DatastoreQueryContext dqc = ctx.getDatastoreContext();
        DataCommonItem issue = dqc.getItem(EventType.issue, applicationId);
        if (dqc.hasErrors()) {
            throw dqc.bundleExceptions();
        }
        MembershipApplicationData application = new MembershipApplicationData(session.login(), issue);
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
                x -> MembershipApplicationData.isUserFeedback(x.body) && MembershipApplicationData.isNewer(x, mostRecentEdit));

        return (comments == null || comments.isEmpty())
                ? null
                : new Feedback(comments.get(0));
    }
}
