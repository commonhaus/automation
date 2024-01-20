package org.commonhaus.automation.github;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.commonhaus.automation.BotConfig;
import org.commonhaus.automation.github.model.Discussion;
import org.commonhaus.automation.github.model.DiscussionCategory;
import org.kohsuke.github.GHIOException;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkus.logging.Log;
import io.smallrye.graphql.client.GraphQLError;
import io.smallrye.graphql.client.Response;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

/**
 * Single-use context for GraphQL query
 * Exceptions and errors are captured for caller in the queryContext
 *
 * @see CFGHApp#getRepoQueryContext(GHRepository, org.kohsuke.github.GHAppInstallation)
 */
public class CFGHQueryHelper {
    private final long ghiId;
    private final GitHubClientProvider gitHubClientProvider;

    protected final BotConfig quarkusBotConfig;
    protected final List<GraphQLError> errors = new ArrayList<>(1);
    protected final List<Throwable> exceptions = new ArrayList<>(1);

    /** Short-lived (minutes) CLI for GitHub REST API */
    GitHub github;
    /** Short-lived (minutes) CLI for GitHub GraphQL API */
    DynamicGraphQLClient graphQLClient;

    CFGHQueryHelper(BotConfig botConfig, long ghiId, GitHubClientProvider gitHubClientProvider) {
        this.ghiId = ghiId;
        this.gitHubClientProvider = gitHubClientProvider;
        this.quarkusBotConfig = botConfig;
    }

    public boolean hasErrors() {
        return !errors.isEmpty() || !exceptions.isEmpty();
    }

    public long getGhiId() {
        return ghiId;
    }

    public void addException(Exception e) {
        exceptions.add(e);
    }

    /** Get GitHub instance for REST API; access should be via subclass (DryRun, logging) */
    protected GitHub getGitHub() {
        if (github == null) {
            github = gitHubClientProvider.getInstallationClient(ghiId);
        }
        return github;
    }

    /** Get GitHub instance for GraphQL API; access should be via helper (DryRun, logging) */
    protected DynamicGraphQLClient getGraphQLClient() {
        if (graphQLClient == null) {
            graphQLClient = gitHubClientProvider.getInstallationGraphQLClient(ghiId);
        }
        return graphQLClient;
    }

    public CFGHQueryHelper addExisting(GitHub github) {
        this.github = github;
        return this;
    }

    public CFGHQueryHelper addExisting(DynamicGraphQLClient graphQLClient) {
        this.graphQLClient = graphQLClient;
        return this;
    }

    @FunctionalInterface
    public interface GitHubParameterApiCall<R> {
        R apply(GitHub gh) throws GHIOException, IOException;
    }

    /**
     * Query helper for repository-scoped interactions
     *
     * @see CFGHApp#getRepoQueryContext(String)
     * @see CFGHApp#getRepoQueryContext(GHRepository, org.kohsuke.github.GHAppInstallation)
     */
    public static class RepoQuery extends CFGHQueryHelper {
        final CFGHRepoInfo repoInfo;

        // All of these artifacts have ties to the underlying GitHub instance,
        // which has a lifespan of minutes at most. Don't hold onto these.
        List<GHLabel> labels;
        List<DiscussionCategory> discussionCategories;
        GHRepository ghRepository;

        RepoQuery(BotConfig botConfig, CFGHRepoInfo repoInfo, GitHubClientProvider gitHubClientProvider) {
            super(botConfig, repoInfo.ghiId, gitHubClientProvider);
            this.repoInfo = repoInfo;
        }

        /**
         * Get GHRepository instance for this repo.
         *
         * @return GHRepository instance or null if there were errors
         */
        public GHRepository getGHRepository() {
            if (ghRepository == null) {
                ghRepository = execGitHubSync(x -> x.getRepository(repoInfo.getFullName()));
            }
            return ghRepository;
        }

        public CFGHRepoInfo getRepoInfo() {
            return repoInfo;
        }

        /**
         * Invoke passed argument with GitHub instance for this repo.
         * Exceptions will be captured in the query context.
         *
         * @param <R> return type
         * @param ghApiCall Function to be invoked with GitHub instance
         * @return result of ghApiCall or null of there were errors
         */
        public <R> R execGitHubSync(GitHubParameterApiCall<R> ghApiCall) {
            if (hasErrors()) {
                return null;
            }
            try {
                return ghApiCall.apply(getGitHub());
            } catch (GHIOException e) {
                // TODO: Config to handle GHIOException (retry? quit? ensure notification?)
                Log.errorf(e, "Error getting repository %s: %s",
                        repoInfo.getFullName(), e.toString());
                if (Log.isDebugEnabled()) {
                    e.getResponseHeaderFields().forEach((k, v) -> Log.debugf("%s: %s", k, v));
                }
                addException(e);
            } catch (IOException e) {
                Log.errorf(e, "Error getting repository %s: %s",
                        repoInfo.getFullName(), e.toString());
                addException(e);
            }
            return null;
        }

        /**
         * Exceptions and errors are captured for caller in the queryContext
         *
         * @param query GraphQL query. Values for owner and name ({@code $name: String!, $owner: String!})
         *        will be provided
         * @return GraphQL Response
         */
        public Response execRepoQuerySync(String query) {
            return execRepoQuerySync(query, new HashMap<>());
        }

        /**
         * Exceptions and errors are captured for caller in the queryContext
         *
         * @param query GraphQL query. Values for repository owner and name ({@code $name: String!, $owner: String!})
         *        will be provided
         * @return GraphQL Response
         */
        public Response execRepoQuerySync(String query, Map<String, Object> variables) {
            if (hasErrors()) {
                return null;
            }
            variables.putIfAbsent("owner", repoInfo.getOwner());
            variables.putIfAbsent("name", repoInfo.getName());

            DynamicGraphQLClient graphqlCLI = getGraphQLClient();
            Response response = null;
            try {
                response = graphqlCLI.executeSync(query, variables);
                if (response.hasError()) {
                    Log.errorf("Error executing GraphQL query for repository %s: %s",
                            repoInfo.getFullName(), response.getErrors());
                    errors.addAll(response.getErrors());
                }
            } catch (ExecutionException | InterruptedException e) {
                Log.errorf(e, "Error executing GraphQL query for repository %s: %s",
                        repoInfo.getFullName(), e.toString());
                exceptions.add(e);
            }
            return response;
        }

        /** Convenience: invoke queryDiscussions on repoInfo with this context (cached data) */
        public List<Discussion> queryDiscussions(boolean b) {
            return repoInfo.queryDiscussions(this, b);
        }

        /** Convenience: invoke queryDiscussionCategories on repoInfo with this context (cached data) */
        public List<DiscussionCategory> queryDiscussionCategories() {
            return repoInfo.queryDiscussionCategories(this);
        }

        public RepoQuery addExisting(GitHub github) {
            super.addExisting(github);
            return this;
        }

        public RepoQuery addExisting(DynamicGraphQLClient graphQLClient) {
            super.addExisting(graphQLClient);
            return this;
        }
    }
}
