package org.commonhaus.automation.github.voting;

import java.time.Instant;

import org.commonhaus.automation.github.EventData;
import org.commonhaus.automation.github.Voting;
import org.commonhaus.automation.github.Voting.Config;
import org.commonhaus.automation.github.model.DataDiscussion;
import org.commonhaus.automation.github.model.EventType;
import org.commonhaus.automation.github.model.QueryHelper.QueryContext;
import org.kohsuke.github.GHOrganization;

public class VoteEvent {
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

    public VoteEvent(QueryContext qc, Config votingConfig, EventData eventData) {
        this.isScheduled = false;
        this.qc = qc;
        this.votingConfig = votingConfig;
        this.eventType = eventData.getEventType();
        this.eventTime = Instant.now().toString();

        this.nodeId = eventData.getNodeId();
        this.nodeUrl = eventData.getNodeUrl();
        this.body = eventData.getBody();
        this.number = eventData.getNumber();
        this.logId = eventData.getLogId();
    }

    public VoteEvent(QueryContext qc, Config votingConfig, DataDiscussion discussion) {
        this.isScheduled = true;
        this.qc = qc;
        this.votingConfig = votingConfig;
        this.eventType = EventType.discussion;
        this.eventTime = Instant.now().toString();

        this.nodeId = discussion.id;
        this.nodeUrl = discussion.url;
        this.number = discussion.number;
        this.body = discussion.body;
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
}
