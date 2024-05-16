package org.commonhaus.automation.github;

import java.util.List;

import org.commonhaus.automation.github.context.ActionType;
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
        this.eventType = EventType.bot_schedule;
        this.logId = "%s:scheduled.%s".formatted(repository.getFullName(), eventType);
    }

    ScheduledQueryContext(ScheduledQueryContext parent, EventType eventType) {
        super(parent);

        this.repository = parent.repository;
        this.eventType = eventType;
        this.logId = "%s:scheduled.%s".formatted(repository.getFullName(), eventType);
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
        return ActionType.bot_scheduled;
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
}
