package org.commonhaus.automation.github.model;

import java.util.List;

import org.commonhaus.automation.AppConfig;
import org.commonhaus.automation.github.model.QueryHelper.QueryContext;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.GitHubClientProvider;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public class ScheduledQueryContext extends QueryContext {
    private String logId;
    final private GHAppInstallation installation;
    final private GHRepository repository;
    private GHOrganization organization;

    ScheduledQueryContext(QueryHelper helper, AppConfig botConfig,
            GitHubClientProvider gitHubClientProvider,
            GHRepository ghRepository, GHAppInstallation installation) {
        super(helper, botConfig, gitHubClientProvider);
        this.repository = ghRepository;
        this.installation = installation;
    }

    @Override
    public String getLogId() {
        String id = logId;
        if (id == null) {
            id = logId = String.format("%s::scheduled", repository.getFullName());
        }
        return id;
    }

    @Override
    public long installationId() {
        return installation.getId();
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
}
