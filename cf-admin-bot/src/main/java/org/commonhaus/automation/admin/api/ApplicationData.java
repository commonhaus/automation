package org.commonhaus.automation.admin.api;

import java.util.Date;
import java.util.regex.Pattern;

import org.commonhaus.automation.github.context.DataCommonComment;
import org.commonhaus.automation.github.context.DataCommonItem;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class ApplicationData {
    static final Pattern CONTRIBUTIONS = Pattern.compile(
            "([\\s\\S]*?<!--CONTRIBUTION::-->)([\\s\\S]*?)(<!--::CONTRIBUTION-->[\\s\\S]*?)", Pattern.CASE_INSENSITIVE);
    static final Pattern NOTES = Pattern.compile("([\\s\\S]*?<!--NOTES::-->)([\\s\\S]*?)(<!--::NOTES-->[\\s\\S]*?)",
            Pattern.CASE_INSENSITIVE);

    transient String issueId;
    transient String title;

    String created;
    String updated;
    String contributions;
    String additionalNotes;
    Feedback feedback;

    public ApplicationData(MemberSession session, DataCommonItem issue) {
        this.title = issue == null ? null : issue.title;
        if (title == null || !ownerEquals(session.login())) {
            return;
        }
        this.issueId = issue.id;
        this.created = issue.createdAt.toString();
        if (issue.updatedAt != null && !issue.updatedAt.equals(issue.createdAt)) {
            this.updated = issue.updatedAt.toString();
        }
        this.contributions = extract(CONTRIBUTIONS, issue.body);
        this.additionalNotes = extract(NOTES, issue.body);
    }

    private String extract(Pattern pattern, String body) {
        var matcher = pattern.matcher(body);
        if (matcher.find()) {
            return matcher.group(2);
        }
        return "";
    }

    public void setFeedback(Feedback feedback) {
        this.feedback = feedback;
    }

    public boolean notOwner() {
        return issueId == null;
    }

    public boolean isOwner() {
        return issueId != null;
    }

    public boolean ownerEquals(String login) {
        return title != null && title.contains("(%s)".formatted(login));
    }

    public record ApplicationPost(
            String contributions,
            String additionalNotes) {
    }

    public static class Feedback {
        final String content;
        final String date;

        public Feedback(String content, String date) {
            this.content = content;
            this.date = date;
        }

        public Feedback(DataCommonComment dataCommonComment) {
            content = dataCommonComment.body.replaceAll("::response::", "").trim();
            date = dataCommonComment.mostRecent().toString();
        }
    }

    public static String updateDescription(ApplicationPost applicationPost) {
        return """
                ## Contribution Details
                <!--CONTRIBUTION::-->
                %s
                <!--::CONTRIBUTION-->
                ## Additional Notes
                <!--NOTES::-->
                %s
                <!--::NOTES-->
                """.formatted(
                applicationPost.contributions(),
                applicationPost.additionalNotes());
    }

    public static String createTitle(MemberSession session) {
        return "Membership application: %s (%s)".formatted(session.name(), session.login());
    }

    public static boolean isUserFeedback(String body) {
        return body.contains("::response::");
    }

    public static boolean isNewer(DataCommonComment x, Date issueMostRecent) {
        return x.mostRecent().after(issueMostRecent);
    }
}
