package org.commonhaus.automation.github;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.commonhaus.automation.BotConfig;
import org.commonhaus.automation.github.model.Discussion;
import org.commonhaus.automation.github.model.DiscussionCategory;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHRepository;

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
    final long ghiId;

    final GitHubClientProvider gitHubClientProvider;
    final BotConfig quarkusBotConfig;
    final List<GraphQLError> errors = new ArrayList<>(1);
    final List<Throwable> exceptions = new ArrayList<>(1);

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

    /**
     * Single-use context for GraphQL query
     * Exceptions and errors are captured for caller in the queryContext
     *
     * @see CFGHApp#getRepoQueryContext(String)
     * @see CFGHApp#getRepoQueryContext(GHRepository, org.kohsuke.github.GHAppInstallation)
     */
    public static class RepoQuery extends CFGHQueryHelper {
        final GHRepository ghRepository;
        final CFGHRepoInfo repoInfo;

        List<GHLabel> labels;
        List<DiscussionCategory> discussionCategories;

        RepoQuery(BotConfig botConfig, CFGHRepoInfo repoInfo, GitHubClientProvider gitHubClientProvider) {
            super(botConfig, repoInfo.ghiId, gitHubClientProvider);
            this.ghRepository = repoInfo.ghRepository;
            this.repoInfo = repoInfo;
        }

        public GHRepository getGhRepository() {
            return ghRepository;
        }

        public CFGHRepoInfo getRepoInfo() {
            return repoInfo;
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
            variables.putIfAbsent("owner", ghRepository.getOwnerName());
            variables.putIfAbsent("name", ghRepository.getName());

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

        /** Convenience: invoke queryDiscussions on repoInfo with this context (cached data) */
        public List<Discussion> queryDiscussions(boolean b) {
            return repoInfo.queryDiscussions(this, b);
        }

        /** Convenience: invoke queryDiscussionCategories on repoInfo with this context (cached data) */
        public List<DiscussionCategory> queryDiscussionCategories() {
            return repoInfo.queryDiscussionCategories(this);
        }
    }
}
