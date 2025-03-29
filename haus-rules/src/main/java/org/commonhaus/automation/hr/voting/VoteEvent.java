package org.commonhaus.automation.hr.voting;

import java.util.List;

import jakarta.json.JsonObject;

import org.commonhaus.automation.ContextService;
import org.commonhaus.automation.github.context.DataCommonComment;
import org.commonhaus.automation.github.context.EventData;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.github.context.JsonAttribute;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;

import io.quarkus.logging.Log;

public class VoteEvent {

    final long installationId;
    final String repoFullName;
    final EventType eventType;
    final String itemNodeId;
    final int number;

    public VoteEvent(long installationId,
            String repoFullName,
            EventType eventType,
            String itemNodeId,
            int number) {
        this.repoFullName = repoFullName;
        this.installationId = installationId;
        this.itemNodeId = itemNodeId;
        this.eventType = eventType;
        this.number = number;
    }

    public EventType getItemType() {
        return eventType;
    }

    public String getItemNodeId() {
        return itemNodeId;
    }

    public long getInstallationId() {
        return installationId;
    }

    public String getRepoFullName() {
        return repoFullName;
    }

    public int getNumber() {
        return number;
    }

    // Create a new ScopedQueryContext when needed, rather than storing one
    public ItemScopedQueryContext createQueryContext(ContextService ctx) {
        return new ItemScopedQueryContext(ctx, this);
    }

    // Generate a unique ID for this vote event (used for queue keys, etc.)
    public String getId() {
        return itemNodeId;
    }

    public String getTaskGroup() {
        return "vote:" + getItemNodeId();
    }

    public String getLogId() {
        return String.format("%d-%s-%d", installationId, repoFullName, number);
    }

    public static EventType eventToType(EventData eventData) {
        return switch (eventData.getEventType()) {
            case discussion, discussion_comment -> EventType.discussion;
            case issue, issues -> EventType.issue;
            case pull_request, pull_request_review -> EventType.pull_request;
            case issue_comment -> {
                JsonObject issue = JsonAttribute.issue.jsonObjectFrom(eventData.getJsonData());
                yield JsonAttribute.pullRequest.existsIn(issue)
                        ? EventType.pull_request
                        : EventType.issue;
            }
            default -> {
                Log.errorf("VotingGitHubEvents.eventToType: unsupported event type %s", eventData.getEventType());
                yield EventType.unknown;
            }
        };
    }

    public static class ItemScopedQueryContext extends ScopedQueryContext {
        final VoteEvent event;

        /** Cache comments for this event (issue specific) */
        List<DataCommonComment> allComments;

        public ItemScopedQueryContext(ContextService ctx, VoteEvent event) {
            super(ctx, event.getInstallationId(), event.getRepoFullName());
            this.event = event;
        }

        public String getItemNodeId() {
            return event.getItemNodeId();
        }

        @Override
        public String getLogId() {
            return event.getLogId();
        }

        public List<DataCommonComment> getCachedComments(String nodeId) {
            return allComments;
        }

        /** Event-scoped comment lookup */
        public void setCachedComments(String nodeId, List<DataCommonComment> comments) {
            allComments = comments;
        }
    }
}
