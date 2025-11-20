package org.commonhaus.automation.hk.member;

import java.util.regex.Pattern;

import org.commonhaus.automation.github.context.DataCommonComment;
import org.commonhaus.automation.github.context.DataCommonItem;
import org.commonhaus.automation.hk.data.ApplicationIssue;
import org.commonhaus.automation.markdown.MarkdownConverter;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class MemberApplication {

    transient String title;

    transient ApplicationIssue appIssue;

    String created;
    String updated;
    String contributions;
    String additionalNotes;
    MemberApplication.Feedback feedback;

    public MemberApplication(String login, DataCommonItem issue) {
        this.title = issue == null ? null : issue.title;
        if (issue == null || title == null || !ownerEquals(login)) {
            return;
        }
        appIssue = ApplicationIssue.fromDataCommonType(issue);
        this.created = issue.createdAt.toString();

        if (issue.lastEditedAt != null && !issue.lastEditedAt.equals(issue.createdAt)) {
            this.updated = issue.lastEditedAt.toString();
        }
        this.contributions = extract(MemberApplicationProcess.CONTRIBUTIONS, issue.body);
        this.additionalNotes = extract(MemberApplicationProcess.NOTES, issue.body);
    }

    private String extract(Pattern pattern, String body) {
        var matcher = pattern.matcher(body);
        if (matcher.find()) {
            return matcher.group(2).trim();
        }
        return "";
    }

    public void setFeedback(MemberApplication.Feedback feedback) {
        this.feedback = feedback;
    }

    @JsonIgnore
    public boolean isValid() {
        return appIssue != null;
    }

    public boolean ownerEquals(String login) {
        return title != null && title.contains("(%s)".formatted(login));
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
}
