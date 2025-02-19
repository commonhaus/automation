package org.commonhaus.automation.github;

import java.util.Collection;
import java.util.List;

import jakarta.json.JsonObject;

import org.commonhaus.automation.github.context.ActionType;
import org.commonhaus.automation.github.context.DataCommonComment;
import org.commonhaus.automation.github.context.DataLabel;
import org.commonhaus.automation.github.context.EventData;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.github.context.JsonAttribute;
import org.commonhaus.automation.github.context.QueryContext;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;

/**
 * QueryContext is a helper class to encapsulate the GitHub API and GraphQL
 * client.
 * <p>
 * It is intended to be used as a short-lived object (minutes) to execute
 * queries.
 * <p>
 * It is not thread-safe.
 *
 * This context is Event-scoped, and is focused on changes to a specific item
 * (issue, pull request, etc.).
 */
public class EventQueryContext extends QueryContext {
    /**
     * Event data used to construct this query context
     */
    private final EventData event;

    /** Cache comments for this event (issue specific) */
    List<DataCommonComment> allComments;

    /** Package private. Constructed by QueryHelper */
    EventQueryContext(AppContextService helper, EventData event) {
        super(helper, event.getInstallationId());
        this.event = event;
    }

    @Override
    public String getLogId() {
        return event.getLogId();
    }

    public String getNodeId() {
        return event.getNodeId();
    }

    @Override
    public String getRepositoryId() {
        return event.getRepositoryId();
    }

    @Override
    public GHRepository getRepository() {
        return event.getRepository();
    }

    @Override
    public GHOrganization getOrganization() {
        return event.getOrganization();
    }

    @Override
    public EventType getEventType() {
        return event.getEventType();
    }

    @Override
    public ActionType getActionType() {
        return event.getActionType();
    }

    public EventData getEventData() {
        return event;
    }

    @Override
    public JsonObject getJsonData() {
        return event.getJsonData();
    }

    public String getStatus() {
        if (event.getActionType() == ActionType.labeled) {
            return JsonAttribute.label.labelFrom(event.getJsonData()).name;
        }
        return event.getActionType().name();
    }

    public Collection<DataLabel> addLabels(List<String> labels) {
        return addLabels(event.getNodeId(), labels);
    }

    public Collection<DataLabel> removeLabels(List<String> labels) {
        return removeLabels(event.getNodeId(), labels);
    }

    public List<DataCommonComment> getCachedComments(String nodeId) {
        return allComments;
    }

    /** Event-scoped comment lookup */
    public void setCachedComments(String nodeId, List<DataCommonComment> comments) {
        allComments = comments;
    }
}
