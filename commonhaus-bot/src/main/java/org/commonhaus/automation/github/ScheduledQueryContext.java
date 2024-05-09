package org.commonhaus.automation.github;

import java.util.List;

import jakarta.json.JsonObject;

import org.commonhaus.automation.AppConfig;
import org.commonhaus.automation.github.context.ActionType;
import org.commonhaus.automation.github.context.DataCommonItem;
import org.commonhaus.automation.github.context.DataDiscussion;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.github.context.QueryContext;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.GitHubClientProvider;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public class ScheduledQueryContext extends QueryContext {

    private final AppConfig botConfig;
    private final QueryHelper helper;
    private final GHRepository repository;
    private final EventType eventType;

    private String logId;
    private GHOrganization organization;

    ScheduledQueryContext(QueryHelper helper, AppConfig botConfig,
            GitHubClientProvider gitHubClientProvider,
            GHRepository ghRepository, long installationId) {
        super(botConfig.isDryRun(), installationId, gitHubClientProvider);

        this.botConfig = botConfig;
        this.helper = helper;
        this.repository = ghRepository;
        this.eventType = EventType.bot_schedule;
    }

    ScheduledQueryContext(ScheduledQueryContext parent, EventType eventType) {
        super(parent.dryRun, parent.installationId, parent.gitHubClientProvider);

        this.botConfig = parent.botConfig;
        this.helper = parent.helper;
        this.repository = parent.repository;
        this.eventType = eventType;
    }

    @Override
    public String getLogId() {
        String id = logId;
        if (id == null) {
            id = logId = String.format("%s:scheduled.%s", repository.getFullName(), eventType);
        }
        return id;
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

    @Override
    public JsonObject getJsonData() {
        return null;
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
