package org.commonhaus.automation.github;

import java.util.List;

import org.commonhaus.automation.github.context.ActionType;
import org.commonhaus.automation.github.context.DataCommonComment;
import org.commonhaus.automation.github.context.DataCommonItem;
import org.commonhaus.automation.github.context.DataDiscussion;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.github.context.QueryContext;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public class ScheduledQueryContext extends QueryContext {

    private final GHRepository repository;
    private final EventType eventType;

    private final String logId;
    private GHOrganization organization;

    ScheduledQueryContext(AppContextService contextService, GHRepository ghRepository, long installationId) {
        super(contextService, installationId);

        this.repository = ghRepository;
        this.eventType = EventType.bot;
        this.logId = "%s:scheduled.%s:%s".formatted(installationId, eventType, repository.getFullName());
    }

    public ScheduledQueryContext(ScheduledQueryContext parent, EventType eventType) {
        super(parent);

        this.repository = parent.repository;
        this.eventType = eventType;
        this.logId = "%s:scheduled.%s:%s".formatted(installationId, eventType, repository.getFullName());
    }

    @Override
    public String getLogId() {
        return logId;
    }

    @Override
    public String getRepositoryId() {
        return this.repository.getNodeId();
    }

    @Override
    public GHRepository getRepository() {
        return this.repository;
    }

    @Override
    public GHOrganization getOrganization() {
        GHOrganization org = this.organization;
        if (org == null) {
            org = this.organization = execGitHubSync((gh, dryRun) -> gh.getOrganization(repository.getOwnerName()));
        }
        return org;
    }

    public EventType getEventType() {
        return eventType;
    }

    @Override
    public ActionType getActionType() {
        return ActionType.bot;
    }

    public ScheduledQueryContext addExisting(GitHub github) {
        super.addExisting(github);
        return this;
    }

    public ScheduledQueryContext addExisting(DynamicGraphQLClient graphQLClient) {
        super.addExisting(graphQLClient);
        return this;
    }

    public List<DataDiscussion> findDiscussionsWithLabel(String label) {
        return DataDiscussion.findDiscussionsWithLabel(this, label);
    }

    public List<DataCommonItem> findIssuesWithLabel(String label) {
        return DataCommonItem.findIssuesWithLabel(this, label);
    }

    /**
     * Item-scoped query context. Safe to cache comment lookups
     */
    public static class ScheduledItemQueryContext extends ScheduledQueryContext {
        /** Cache comments for this event (issue specific) */
        List<DataCommonComment> allComments;

        public ScheduledItemQueryContext(ScheduledQueryContext parent, EventType eventType) {
            super(parent, eventType);
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
