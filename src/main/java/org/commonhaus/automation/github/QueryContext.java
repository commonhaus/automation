package org.commonhaus.automation.github;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.commonhaus.automation.BotConfig;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHProject;
import org.kohsuke.github.GHRepository;

import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkus.logging.Log;
import io.smallrye.graphql.client.GraphQLError;
import io.smallrye.graphql.client.Response;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

/**
 * Single-use context for GraphQL query
 * Exceptions and errors are captured for caller in the queryContext
 */
public class QueryContext {
    final GHRepository ghRepository;
    final long ghiId;
    
    final GitHubClientProvider gitHubClientProvider;
    final BotConfig quarkusBotConfig;
    final List<GraphQLError> errors = new ArrayList<>(1);
    final List<Throwable> exceptions = new ArrayList<>(1);

    public QueryContext(BotConfig botConfig, GHRepository ghRepository, long ghiId, GitHubClientProvider gitHubClientProvider) {
        this.ghRepository = ghRepository;
        this.ghiId = ghiId;
        this.gitHubClientProvider = gitHubClientProvider;
        this.quarkusBotConfig = botConfig;
    }

    public boolean hasErrors() {
        return !errors.isEmpty() || !exceptions.isEmpty();
    }

    public GHRepository getGhRepository() {
        return ghRepository;
    }

    public long getGhiId() {
        return ghiId;
    }

    public List<GHLabel> getLabels() {
        if (hasErrors()) {
            return List.of();
        }
        try {
            return ghRepository.listLabels().toList();
        } catch (IOException e) {
            Log.errorf(e, "Error executing GraphQL query for repository %s: %s", 
                    ghRepository.getFullName(), e.toString());
            exceptions.add(e);
        }
        return List.of();
    }

    public List<GHProject> getProjects() {
        if (hasErrors()) {
            return List.of();
        }
        try {
            return ghRepository.listProjects().toList();
        } catch (IOException e) {
            Log.errorf(e, "Error executing GraphQL query for repository %s: %s", 
                    ghRepository.getFullName(), e.toString());
            exceptions.add(e);
        }
        return List.of();
    }

    /**
     * Exceptions and errors are captured for caller in the queryContext
     * @param query GraphQL query. Values for owner and name ({@code $name: String!, $owner: String!}) 
     * will be provided
     * @return GraphQL Response
     */
    public Response execRepoQuerySync(String query) {
        return execRepoQuerySync(query, new HashMap<>());
    }

    /**
     * Exceptions and errors are captured for caller in the queryContext
     * @param query GraphQL query. Values for owner and name ({@code $name: String!, $owner: String!}) 
     * will be provided
     * @return GraphQL Response
     */
    public Response execRepoQuerySync(String query, Map<String, Object> variables) {
        if (hasErrors()) {
            return null;
        }
        variables.put("owner", ghRepository.getOwnerName());
        variables.put("name", ghRepository.getName()); // replace with actual repo name

        DynamicGraphQLClient graphqlCLI = gitHubClientProvider.getInstallationGraphQLClient(ghiId);
        Response response = null;
        try {
            response = graphqlCLI.executeSync(query, variables);
            if (response.hasError()) {
                Log.errorf("Error executing GraphQL query for repository %s: %s", 
                    ghRepository.getFullName(), response.getErrors());
                errors.addAll(response.getErrors());
            }
        } catch (ExecutionException | InterruptedException e) {
            Log.errorf(e, "Error executing GraphQL query for repository %s: %s", 
                    ghRepository.getFullName(), e.toString());
            exceptions.add(e);
        }
        return response;
    }
}
