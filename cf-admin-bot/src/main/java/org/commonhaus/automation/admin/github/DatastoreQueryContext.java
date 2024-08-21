package org.commonhaus.automation.admin.github;

import org.commonhaus.automation.github.context.QueryContext;
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
public class DatastoreQueryContext extends QueryContext {
    final AppContextService ctx;
    final String repoFullName;
    final String ownerName;

    private GHRepository repository;
    private final TokenGitHubClients tokenClients;

    public DatastoreQueryContext(AppContextService ctx, TokenGitHubClients tokenClients, String repoFullName) {
        super(ctx, -1);
        this.ctx = ctx;
        this.repoFullName = repoFullName;
        this.ownerName = QueryContext.toOrganizationName(repoFullName);
        this.tokenClients = tokenClients;
    }

    @Override
    public String getLogId() {
        return repoFullName;
    }

    public DatastoreQueryContext addExisting(GitHub github) {
        super.addExisting(github);
        return this;
    }

    public DatastoreQueryContext addExisting(DynamicGraphQLClient graphQLClient) {
        super.addExisting(graphQLClient);
        return this;
    }

    @Override
    public String getRepositoryId() {
        GHRepository repo = getRepository();
        return repo == null ? null : getRepository().getNodeId();
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
