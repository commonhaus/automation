package org.commonhaus.automation.admin.api;

import java.util.Date;
import java.util.regex.Pattern;

import org.commonhaus.automation.admin.api.CommonhausUser.MembershipApplication;
import org.commonhaus.automation.github.context.DataCommonComment;
import org.commonhaus.automation.github.context.DataCommonItem;
import org.commonhaus.automation.github.context.DataLabel;
import org.commonhaus.automation.markdown.MarkdownConverter;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.quarkus.logging.Log;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class MembershipApplicationData {
    public static final String NEW = "application/new";
    public static final String ACCEPTED = "application/accepted";
    public static final String DECLINED = "application/declined";

    static final Pattern CONTRIBUTIONS = Pattern.compile(
            "([\\s\\S]*?<!--CONTRIBUTION::-->)([\\s\\S]*?)(<!--::CONTRIBUTION-->[\\s\\S]*?)", Pattern.CASE_INSENSITIVE);
    static final Pattern NOTES = Pattern.compile("([\\s\\S]*?<!--NOTES::-->)([\\s\\S]*?)(<!--::NOTES-->[\\s\\S]*?)",
            Pattern.CASE_INSENSITIVE);
    static final Pattern NOTIFICATION = Pattern.compile("<!-- notify::(\\S+?) -->");
    static final Pattern STRIP_COMMENTS = Pattern.compile("<!--:?:?(CONTRIBUTION|NOTES):?:?-->", Pattern.CASE_INSENSITIVE);

    transient String title;
    transient MembershipApplication application;

    String created;
    String updated;
    String contributions;
    String additionalNotes;
    Feedback feedback;

    public MembershipApplicationData(String login, DataCommonItem issue) {
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

    public record ApplicationPost(
            String contributions,
            String additionalNotes) {
    }

    public static class Feedback {
        final String htmlContent;
        final String date;

        public Feedback(String htmlContent, String date) {
            this.htmlContent = htmlContent;
            this.date = date;
        }

        public Feedback(DataCommonComment dataCommonComment) {
            String content = dataCommonComment.body.replaceAll("::response::", "").trim();

            this.htmlContent = MarkdownConverter.toHtml(content);
            date = dataCommonComment.mostRecentEdit().toString();
        }
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
                """.formatted(
                session.login(),
                session.url(),
                STRIP_COMMENTS.matcher(applicationPost.contributions()).replaceAll(" "),
                STRIP_COMMENTS.matcher(applicationPost.additionalNotes()).replaceAll(" "),
                notificationEmail == null ? "" : "<!-- notify::%s -->".formatted(notificationEmail));
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

    public static boolean isAccepted(DataLabel label) {
        return ACCEPTED.equals(label.name);
    }

    public static boolean isDeclined(DataLabel label) {
        return DECLINED.equals(label.name);
    }

    public static boolean isNewer(DataCommonComment x, Date issueMostRecent) {
        Log.debugf("isNewer: %s %s", x.mostRecentEdit(), issueMostRecent);
        return x.mostRecentEdit().after(issueMostRecent);
    }
}
