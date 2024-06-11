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
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHUser;

@ApplicationScoped
public class MemberApplicationProcess {

    @Inject
    AppContextService ctx;

    @Inject
    CommonhausDatastore datastore;

    public void handleApplicationEvent(ScopedQueryContext qc, GHIssue issue, DataCommonItem item, DataLabel label) {
        if (!ApplicationData.isMemberApplicationEvent(item, label)) {
            return;
        }

        String login = ApplicationData.getLogin(item);
        GHUser applicant = qc.getUser(login);
        CommonhausUser user = datastore.getCommonhausUser(login, applicant.getId(), false, false);
        if (user == null) {
            return;
        }

        ApplicationData applicationData = new ApplicationData(login, item);
        if (!applicationData.isValid()) {
            // TODO: do we fix bad data from this side? (hasn't happened yet.. )
            ctx.logAndSendEmail(qc.getLogId(), "Invalid application data",
                    "Unable to find valid application data for login %s and issue %s (%s)"
                            .formatted(login, item.id, item.title),
                    null, null);
            return;
        }

        if (ApplicationData.isAccepted(label)) {
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
            String teamFullName = ctx.getTeamForRole("member");
            if (teamFullName != null && !qc.hasErrors()) {
                qc.addTeamMember(applicant, teamFullName);
            }
        } else {
            datastore.setCommonhausUser(new UpdateEvent(user,
                    (c, u) -> {
                        if (u.status().updateFromPending()) {
                            u.status(MemberStatus.DECLINED);
                        }
                    },
                    "Membership application declined",
                    true,
                    true));
        }
        if (!qc.hasErrors()) {
            qc.closeIssue(issue);
            qc.removeLabels(item.id, List.of(ApplicationData.NEW));
        }
    }

    public ApplicationData userUpdateApplicationIssue(
            MemberSession session,
            ScopedQueryContext qc,
            ApplicationData applicationData,
            ApplicationPost applicationPost) {

        String content = ApplicationData.issueContent(session, applicationPost);
        Collection<DataLabel> labels = qc.findLabels(List.of(ApplicationData.NEW));
        MembershipApplication application = applicationData == null ? null : applicationData.application;

        DataCommonItem item = application == null
                ? qc.createItem(EventType.issue,
                        ApplicationData.createTitle(session),
                        content,
                        labels)
                : qc.updateItemDescription(EventType.issue, application.nodeId(), content, DataCommonItem.ISSUE_FIELDS);

        return item == null
                ? null
                : new ApplicationData(session.login(), item);
    }

    ApplicationData findUserApplication(MemberSession session, String applicationId, boolean withComments) {
        ScopedQueryContext qc = ctx.getDatastoreContext();
        DataCommonItem issue = qc.getItem(EventType.issue, applicationId);
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
