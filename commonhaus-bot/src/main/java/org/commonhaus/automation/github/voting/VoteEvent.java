package org.commonhaus.automation.github.voting;

import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.json.JsonObject;

import org.commonhaus.automation.github.AppContextService.AppQueryContext;
import org.commonhaus.automation.github.context.ActionType;
import org.commonhaus.automation.github.context.BotComment;
import org.commonhaus.automation.github.context.DataCommonComment;
import org.commonhaus.automation.github.context.DataCommonItem;
import org.commonhaus.automation.github.context.DataPullRequestReview;
import org.commonhaus.automation.github.context.EventData;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.github.context.JsonAttribute;
import org.kohsuke.github.GHOrganization;

import io.quarkus.qute.TemplateData;

@TemplateData
public class VoteEvent {
    public final static String ADDRESS = "voting";
    public final static String MANUAL_ADDRESS = "voting-manual";

    public final static String MANUAL_VOTE_RESULT = "vote::result";

    // standard prefix, used when no badge is configured
    public static final String prefixMatch = "\\*\\*Vote progress\\*\\* tracked in \\[this comment]";
    // when a badge is configured, this is the prefix
    public static final String badgeMatch = "\\[!\\[.*?]\\(.*?\\)]";
    public static final String linkMatch = "\\[.*?Vote progress]";

    public static final Pattern botCommentPattern = Pattern.compile(
            "(?:" + prefixMatch + "|" + badgeMatch + "|" + linkMatch + ")" +
                    "\\(([^ )]+) ?(?:\"([^\"]+)\")?\\)\\.?", // the juicy part of the URL
            Pattern.CASE_INSENSITIVE);

    private final ActionType actionType;
    private final AppQueryContext qc;
    private final VoteConfig votingConfig;

    private final String body;
    /** Discussion or Issue/PullRequest */
    private final EventType itemType;
    private final String eventTime;

    private final String nodeId;
    /** HtmlURL of existing node containing votes */
    private final String nodeUrl;
    /** Repo-scoped Number of existing node containing votes */
    private final int number;
    private final boolean isClosed;

    private final String logId;
    private List<DataPullRequestReview> prReviews;

    public VoteEvent(AppQueryContext qc, VoteConfig votingConfig, EventData eventData) {
        this.actionType = eventData.getActionType();
        this.qc = qc;
        this.votingConfig = votingConfig;

        this.itemType = switch (eventData.getEventType()) {
            case discussion, discussion_comment -> EventType.discussion;
            case issue -> EventType.issue;
            case pull_request, pull_request_review -> EventType.pull_request;
            case issue_comment -> {
                JsonObject issue = JsonAttribute.issue.jsonObjectFrom(eventData.getJsonData());
                yield JsonAttribute.pullRequest.existsIn(issue)
                        ? EventType.pull_request
                        : EventType.issue;
            }
            default -> {
                throw new IllegalArgumentException("Unsupported event type " + qc.getEventType());
            }
        };
        this.eventTime = Instant.now().toString();

        this.nodeId = eventData.getNodeId();
        this.nodeUrl = eventData.getNodeUrl();
        this.body = eventData.getBody();
        this.number = eventData.getNumber();
        this.logId = eventData.getLogId();
        this.isClosed = eventData.isClosed();
    }

    public VoteEvent(AppQueryContext qc, VoteConfig votingConfig, DataCommonItem item, EventType type) {
        this.actionType = ActionType.bot_scheduled;
        this.qc = qc;
        this.votingConfig = votingConfig;
        this.itemType = type;
        this.eventTime = Instant.now().toString();

        this.nodeId = item.id;
        this.nodeUrl = item.url;
        this.number = item.number;
        this.body = item.body;
        this.logId = qc.getLogId() + "#" + number;
        this.isClosed = item.closedAt != null;
    }

    public boolean isScheduled() {
        return actionType == ActionType.bot_scheduled;
    }

    public AppQueryContext getQueryContext() {
        return qc;
    }

    public VoteConfig getVotingConfig() {
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

    public boolean itemIsClosed() {
        return isClosed;
    }

    public boolean itemIsOpen() {
        return !isClosed;
    }

    public String getBody() {
        return body;
    }

    public EventType getItemType() {
        return itemType;
    }

    public ActionType getActionType() {
        return actionType;
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
        return itemType.isPullRequest();
    }

    public Pattern commentPattern() {
        return botCommentPattern;
    }

    public List<DataPullRequestReview> getReviews() {
        if (!itemType.isPullRequest()) {
            return List.of();
        }
        if (prReviews == null) {
            prReviews = qc.queryReviews(this.nodeId);
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

    public static boolean isManualVoteResult(AppQueryContext qc, VoteConfig votingConfig, DataCommonComment comment) {
        return qc.isLoginIncluded(comment.author.login, votingConfig.managers) && comment.body.contains(MANUAL_VOTE_RESULT);
    }

    public static class ManualVoteEvent extends VoteEvent {
        private final DataCommonComment comment;

        public ManualVoteEvent(AppQueryContext qc, org.commonhaus.automation.github.voting.VoteConfig votingConfig,
                EventData eventData, DataCommonComment comment) {
            super(qc, votingConfig, eventData);
            this.comment = comment;
        }

        public DataCommonComment getComment() {
            return comment;
        }
    }
}
