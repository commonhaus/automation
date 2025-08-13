package org.commonhaus.automation.hm.github;

import org.commonhaus.automation.github.context.GitHubQueryContext;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.TokenGitHubClients;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

/**
 * Connection to Datastore repository (with issue/content write privileges)
 * is managed by PAT, rather than GH App permissions (to reduce the permissions
 * required by the app overall)
 */
public class ReportQueryContext extends GitHubQueryContext {
    final AppContextService ctx;
    final String repoFullName;
    final String ownerName;

    private GHRepository repository;
    private final TokenGitHubClients tokenClients;
    private String logId;

    public ReportQueryContext(AppContextService ctx, TokenGitHubClients tokenClients, String organization, String repoName) {
        super(ctx, -1);
        this.ctx = ctx;
        this.ownerName = organization;
        this.repoFullName = toFullName(organization, repoName);
        this.tokenClients = tokenClients;
        this.logId = "hrqc";
    }

    @Override
    public String getLogId() {
        return logId;
    }

    public ReportQueryContext withLogId(String logId) {
        this.logId = "hrqc-" + logId;
        return this;
    }

    public ReportQueryContext withExisting(GitHub github) {
        super.withExisting(github);
        return this;
    }

    public ReportQueryContext withExisting(DynamicGraphQLClient graphQLClient) {
        super.withExisting(graphQLClient);
        return this;
    }

    @Override
    public String getRepositoryId() {
        GHRepository repo = getRepository();
        return repo == null ? null : repo.getNodeId();
    }

    @Override
    public GHRepository getRepository() {
        if (repository == null && repoFullName != null) {
            repository = getRepository(repoFullName);
        }
        return repository;
    }

    @Override
    public GHOrganization getOrganization() {
        return getOrganization(ownerName);
    }

    @Override
    public GitHub getGitHub() {
        return tokenClients.getRestClient();
    }

    @Override
    public DynamicGraphQLClient getGraphQLClient() {
        return tokenClients.getGraphQLClient();
    }
}
