package org.commonhaus.automation.github.voting;

import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.commonhaus.automation.github.EventData;
import org.commonhaus.automation.github.Voting;
import org.commonhaus.automation.github.Voting.Config;
import org.commonhaus.automation.github.model.DataCommonItem;
import org.commonhaus.automation.github.model.DataPullRequestReview;
import org.commonhaus.automation.github.model.EventType;
import org.commonhaus.automation.github.model.QueryHelper.BotComment;
import org.commonhaus.automation.github.model.QueryHelper.QueryContext;
import org.kohsuke.github.GHOrganization;

import io.quarkus.qute.TemplateData;

@TemplateData
public class VoteEvent {
    public final static String ADDRESS = "voting";

    // standard prefix, used when no badge is configured
    public static final String prefixMatch = "\\*\\*Vote progress\\*\\* tracked in \\[this comment\\]";
    // when a badge is configured, this is the prefix
    public static final String badgeMatch = "\\[!\\[.*?\\]\\(.*?\\)\\]";
    public static final String linkMatch = "\\[.*?Vote progress\\]";

    public static final Pattern botCommentPattern = Pattern.compile(
            "(?:" + prefixMatch + "|" + badgeMatch + "|" + linkMatch + ")" +
                    "\\(([^ )]+) ?(?:\"([^\"]+)\")?\\)\\.?", // the juicy part of the URL
            Pattern.CASE_INSENSITIVE);

    private final boolean isScheduled;
    private final QueryContext qc;
    private final Voting.Config votingConfig;

    private final String body;
    /** Discussion or Issue/PullRequest */
    private final EventType eventType;
    private final String eventTime;

    /** Id of existing node containing votes */
    private final String nodeId;
    /** HtmlURL of existing node containing votes */
    private final String nodeUrl;
    /** Repo-scoped Number of existing node containing votes */
    private final int number;

    private final String logId;
    private List<DataPullRequestReview> prReviews;

    public VoteEvent(QueryContext qc, Config votingConfig, EventData eventData) {
        this.isScheduled = false;
        this.qc = qc;
        this.votingConfig = votingConfig;
        this.eventType = qc.getEventType();
        this.eventTime = Instant.now().toString();

        this.nodeId = eventData.getNodeId();
        this.nodeUrl = eventData.getNodeUrl();
        this.body = eventData.getBody();
        this.number = eventData.getNumber();
        this.logId = eventData.getLogId();
    }

    public VoteEvent(QueryContext qc, Config votingConfig, DataCommonItem item) {
        this.isScheduled = true;
        this.qc = qc;
        this.votingConfig = votingConfig;
        this.eventType = qc.getEventType();
        this.eventTime = Instant.now().toString();

        this.nodeId = item.id;
        this.nodeUrl = item.url;
        this.number = item.number;
        this.body = item.body;
        this.logId = qc.getLogId() + "#" + number;
    }

    public boolean isScheduled() {
        return isScheduled;
    }

    public QueryContext getQueryContext() {
        return qc;
    }

    public Config getVotingConfig() {
        return votingConfig;
    }

    public String getId() {
        return nodeId;
    }

    public String getUrl() {
        return nodeUrl;
    }

    public int getNumber() {
        return number;
    }

    public String getBody() {
        return body;
    }

    public EventType getEventType() {
        return eventType;
    }

    public String getRepoSlug() {
        return qc.getRepository().getFullName();
    }

    public String getLogId() {
        return logId;
    }

    public GHOrganization getOrganization() {
        return qc.getOrganization();
    }

    /** Used in error email */
    public String eventTime() {
        return eventTime;
    }

    public boolean isPullRequest() {
        return eventType != null && eventType.isPullRequest();
    }

    public Pattern commentPattern() {
        return botCommentPattern;
    }

    public List<DataPullRequestReview> getReviews() {
        if (!eventType.isPullRequest()) {
            return List.of();
        }
        if (prReviews == null) {
            prReviews = DataPullRequestReview.queryReviews(qc, this.nodeId);
            // The bot's reviews are not counted
            prReviews.removeIf(x -> qc.isBot(x.author.login));
        }
        return prReviews;
    }

    public String getVoteHeaderText(BotComment comment) {
        if (votingConfig.status != null && votingConfig.status.badge != null) {
            String badgeLink = votingConfig.status.badge
                    .replace("{{repoName}}", qc.getRepository().getFullName())
                    .replace("{{number}}", number + "");

            return comment.markdownLink("![🗳️ Vote progress](" + badgeLink + ")");
        } else {
            return comment.markdownLink("🗳️ Vote progress");
        }
    }

    public String updateItemText(BotComment comment) {
        String prefix = getVoteHeaderText(comment);
        Matcher matcher = botCommentPattern.matcher(body);
        if (matcher.find()) {
            return matcher.replaceFirst(prefix);
        }
        return prefix + "\r\n\r\n" + body;
    }
}
