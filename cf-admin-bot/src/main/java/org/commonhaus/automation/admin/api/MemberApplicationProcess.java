package org.commonhaus.automation.admin.api;

import java.util.Collection;
import java.util.Date;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.commonhaus.automation.admin.api.ApplicationData.ApplicationPost;
import org.commonhaus.automation.admin.api.ApplicationData.Feedback;
import org.commonhaus.automation.admin.api.CommonhausUser.MemberStatus;
import org.commonhaus.automation.admin.api.CommonhausUser.MembershipApplication;
import org.commonhaus.automation.admin.github.AppContextService;
import org.commonhaus.automation.admin.github.CommonhausDatastore;
import org.commonhaus.automation.admin.github.CommonhausDatastore.UpdateEvent;
import org.commonhaus.automation.admin.github.ScopedQueryContext;
import org.commonhaus.automation.github.context.DataCommonComment;
import org.commonhaus.automation.github.context.DataCommonItem;
import org.commonhaus.automation.github.context.DataLabel;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.markdown.MarkdownConverter;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.ReactionContent;

import io.quarkus.logging.Log;

@ApplicationScoped
public class MemberApplicationProcess {

    @Inject
    AppContextService ctx;

    @Inject
    CommonhausDatastore datastore;

    /**
     * Handle an application comment event
     *
     * @param qc
     * @param issue
     * @param item
     */
    public void handleApplicationComment(ScopedQueryContext qc, DataCommonItem issue, DataCommonComment comment) {
        if (ApplicationData.isUserFeedback(comment.body)) {
            Log.debugf("[%s] updateApplicationComments: #%s - user feedback", qc.getLogId(), issue.number);
            String notificationEmail = ApplicationData.getNotificationEmail(issue);
            if (notificationEmail != null) {
                String body = """
                        Your membership application has been updated. Please review the feedback and make any necessary updates.

                        ---

                        %s

                        ---

                        If you have any questions or need further clarification, please find us on Discord or send an email to hello@commonhaus.org.

                        """
                        .formatted(comment.body.replaceAll("::response::", "").trim());
                String htmlBody = MarkdownConverter.toHtml(body);
                ctx.sendEmail(qc.getLogId(),
                        "Commonhaus Foundation Membership Application",
                        body, htmlBody, new String[] { notificationEmail });
            }
        } else {
            Log.debugf("[%s] updateApplicationComments: #%s - not user feedback", qc.getLogId(), issue.number);
        }
    }

    /**
     * Handle an application event (issue label change) for a membership
     * application.
     *
     * @param qc QueryContext for the repository containing the issue
     * @param issue The issue that was updated
     * @param item The issue as Json-derived data
     * @param label The label that was added
     */
    public void handleApplicationLabelAdded(ScopedQueryContext qc, GHIssue issue, DataCommonItem item,
            DataLabel label) {
        String login = ApplicationData.getLogin(item);
        GHUser applicant = qc.getUser(login);

        String teamFullName = ctx.getTeamForRole(CommonhausUser.MEMBER_ROLE);
        if (teamFullName == null) {
            // should not happen, but in case something gets misaligned...
            ctx.logAndSendEmail(qc.getLogId(), "Missing configuration",
                    "Unable to find team for " + CommonhausUser.MEMBER_ROLE,
                    null, null);
            qc.addBotReaction(item.id, ReactionContent.CONFUSED);
            return;
        }

        CommonhausUser user = datastore.getCommonhausUser(login, applicant.getId(), false, false);
        if (user == null) {
            // should not happen, but in case something gets misaligned...
            ctx.logAndSendEmail(qc.getLogId(), "Invalid application data",
                    "Label added to an application for an unknown user (%s)".formatted(login),
                    null, null);
            qc.addBotReaction(item.id, ReactionContent.CONFUSED);
            return;
        }

        ApplicationData applicationData = new ApplicationData(login, item);
        // We haven't approved/declined this member yet: we need a valid application
        if (user.isMember == null && !applicationData.isValid()) {
            ctx.logAndSendEmail(qc.getLogId(), "Invalid application data",
                    "Unable to find valid application data for login %s and issue %s (%s)"
                            .formatted(login, item.id, item.title),
                    null, null);
            qc.addBotReaction(item.id, ReactionContent.CONFUSED);
            return;
        }

        String notificationEmail = ApplicationData.getNotificationEmail(item);
        if (ApplicationData.isAccepted(label)) {
            if (user.isMember == null) {
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
            // (re-)try adding the user to the team
            if (!qc.hasErrors()
                    && ctx.addTeamMember(applicant, teamFullName)
                    && notificationEmail != null) {
                String body = """
                        🎉 Congratulations! 🎉

                        Your membership application has been accepted.
                        You are now a member of the Commonhaus Foundation.

                        Please visit the Members-only section of the website for next steps:
                        https://www.commonhaus.org/member/

                        If you have any questions, please find us on Discord or send an email to hello@commonhaus.org.

                        🥰 🚀
                        """;
                String htmlBody = MarkdownConverter.toHtml(body);
                ctx.sendEmail(qc.getLogId(),
                        "Welcome to the Commonhaus Foundation!",
                        body, htmlBody, new String[] { notificationEmail });
            }
        } else if (user.isMember == null && ApplicationData.isDeclined(label)) {
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

            if (!qc.hasErrors() && notificationEmail != null) {
                String body = """
                        🙏 Thank you for your interest in the Commonhaus Foundation. 🙏

                        We regret to inform you that your membership application has not been accepted at this time.

                        If you have any questions or need further clarification, please find us on Discord or send an email to hello@commonhaus.org.

                        We appreciate your understanding and interest in our community.

                        🌱 🚀
                        """;
                String htmlBody = MarkdownConverter.toHtml(body);
                ctx.sendEmail(qc.getLogId(),
                        "Commonhaus Foundation Membership Application",
                        body, htmlBody, new String[] { notificationEmail });
            }
        }

        if (qc.hasErrors()) {
            qc.addBotReaction(item.id, ReactionContent.CONFUSED);
        } else {
            qc.removeBotReaction(item.id, ReactionContent.CONFUSED);
            qc.closeIssue(issue);
            qc.removeLabels(item.id, List.of(ApplicationData.NEW));
        }
    }

    /**
     * User action called when a user submits or updates their membership
     * application
     *
     * @param session User session
     * @param qc Datastore QueryContext (for making changes to the
     *        application issue)
     * @param applicationData Current application data
     * @param applicationPost Application data from the user
     * @return updated ApplicationData object or null on error (see QueryContext for
     *         errors)
     */
    public ApplicationData userUpdateApplicationIssue(
            MemberSession session,
            ScopedQueryContext qc,
            ApplicationData applicationData,
            ApplicationPost applicationPost,
            String notificationEmail) {

        String content = ApplicationData.issueContent(session, applicationPost, notificationEmail);
        Collection<DataLabel> labels = qc.findLabels(List.of(ApplicationData.NEW));
        MembershipApplication application = applicationData == null ? null : applicationData.application;

        DataCommonItem item = application == null
                ? qc.createItem(EventType.issue,
                        ApplicationData.createTitle(session),
                        content,
                        labels)
                : qc.updateItemDescription(EventType.issue, application.nodeId(), content, DataCommonItem.ISSUE_FIELDS);

        if (qc.hasErrors() && item != null) {
            qc.addBotReaction(item.id, ReactionContent.CONFUSED);
        }

        return item == null
                ? null
                : new ApplicationData(session.login(), item);
    }

    ApplicationData findUserApplication(MemberSession session, String applicationId, boolean withComments)
            throws Throwable {
        ScopedQueryContext qc = ctx.getDatastoreContext();
        DataCommonItem issue = qc.getItem(EventType.issue, applicationId);
        if (qc.hasErrors()) {
            throw qc.bundleExceptions();
        }
        ApplicationData application = new ApplicationData(session.login(), issue);
        if (application.isValid() && withComments) {
            Feedback feedback = getFeedback(qc, applicationId, issue.mostRecentEdit());
            if (feedback != null) {
                application.setFeedback(feedback);
            }
        }
        return application;
    }

    Feedback getFeedback(ScopedQueryContext qc, String nodeId, Date mostRecentEdit) {
        List<DataCommonComment> comments = qc.getComments(nodeId,
                x -> ApplicationData.isUserFeedback(x.body) && ApplicationData.isNewer(x, mostRecentEdit));

        return (comments == null || comments.isEmpty())
                ? null
                : new Feedback(comments.get(0));
    }
}
