package org.commonhaus.automation.github;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.commonhaus.automation.AppConfig;
import org.commonhaus.automation.github.model.DataLabel;
import org.kohsuke.github.GHIOException;
import org.kohsuke.github.GitHub;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkus.logging.Log;
import io.smallrye.graphql.client.GraphQLError;
import io.smallrye.graphql.client.Response;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

@ApplicationScoped
public class QueryHelper {
    static final String LABEL = "labels";
    static Cache<String, Object> cache = Caffeine.newBuilder()
            .expireAfterWrite(6, TimeUnit.HOURS)
            .build();

    @Inject
    AppConfig botConfig;

    @Inject
    GitHubClientProvider gitHubClientProvider;

    public QueryContext newQueryContext(EventData event) {
        return new QueryContext(botConfig, gitHubClientProvider, event);
    }

    public QueryContext newQueryContext(EventData eventData, GitHub github) {
        return newQueryContext(eventData).addExisting(github);
    }

    public static <T> T getCache(String itemId, String key, Class<T> type) {
        String idx = itemId + ":" + key;
        T result = (T) cache.getIfPresent(idx);
        if (result != null) {
            System.out.println(":: HIT :: " + idx + " ::: ");
        } else {
            System.out.println(":: MISS :: " + idx + " ::: ");
        }
        return result;
    }

    public static void putCache(String itemId, String key, Object value) {
        String idx = itemId + ":" + key;
        System.out.println(":: UPDATE :: " + idx + " ::: ");
        cache.put(idx, value);
    }

    /**
     * QueryContext is a helper class to encapsulate the GitHub API and GraphQL
     * client.
     * <p>
     * It is intended to be used as a short-lived object (minutes) to execute
     * queries.
     * <p>
     * It is not thread-safe.
     */
    public static class QueryContext {
        protected final AppConfig botConfig;

        private final GitHubClientProvider gitHubClientProvider;

        protected final List<GraphQLError> errors = new ArrayList<>(1);
        protected final List<Throwable> exceptions = new ArrayList<>(1);

        /** Short-lived (minutes) CLI for GitHub REST API */
        GitHub github;
        /** Short-lived (minutes) CLI for GitHub GraphQL API */
        DynamicGraphQLClient graphQLClient;

        final EventData evt;

        QueryContext(AppConfig botConfig, GitHubClientProvider gitHubClientProvider, EventData event) {
            this.botConfig = botConfig;
            this.gitHubClientProvider = gitHubClientProvider;

            // unpack Json-B from string
            this.evt = event;
        }

        public EventData getEventData() {
            return evt;
        }

        public boolean hasErrors() {
            return !errors.isEmpty() || !exceptions.isEmpty();
        }

        public void addException(Exception e) {
            exceptions.add(e);
        }

        public QueryContext addExisting(GitHub github) {
            this.github = github;
            return this;
        }

        public QueryContext addExisting(DynamicGraphQLClient graphQLClient) {
            this.graphQLClient = graphQLClient;
            return this;
        }

        /**
         * Get GitHub instance for GraphQL API; access should be via helper (DryRun,
         * logging)
         */
        protected DynamicGraphQLClient getGraphQLClient() {
            if (graphQLClient == null) {
                graphQLClient = gitHubClientProvider.getInstallationGraphQLClient(evt.installationId());
            }
            return graphQLClient;
        }

        /**
         * Get GitHub instance for REST API; access should be via subclass (DryRun,
         * logging)
         */
        protected GitHub getGitHub() {
            if (github == null) {
                github = gitHubClientProvider.getInstallationClient(evt.installationId());
            }
            return github;
        }

        @FunctionalInterface
        public interface GitHubParameterApiCall<R> {
            R apply(GitHub gh) throws GHIOException, IOException;
        }

        /**
         * Invoke passed argument with GitHub instance.
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
                Log.errorf(e, "[%s] Error making GH Request: %s", evt.installationId(), e.toString());
                if (Log.isDebugEnabled()) {
                    e.getResponseHeaderFields().forEach((k, v) -> Log.debugf("%s: %s", k, v));
                }
                addException(e);
            } catch (IOException e) {
                Log.errorf(e, "[%s] Error making GH Request: %s", evt.installationId(), e.toString());
                addException(e);
            }
            return null;
        }

        /**
         * Exceptions and errors are captured for caller in the queryContext
         *
         * @param query GraphQL query.
         * @return GraphQL Response
         */
        public Response execQuerySync(String query, Map<String, Object> variables) {
            if (hasErrors()) {
                return null;
            }
            if (botConfig.isDryRun() && query.contains("mutation")) {
                Log.infof("DryRun would execute the following query :: %s :: with attributes :: %s", query, variables);
                return null;
            }

            DynamicGraphQLClient graphqlCLI = getGraphQLClient();
            Response response = null;
            try {
                response = graphqlCLI.executeSync(query, variables);
                Log.debugf("result ? %s", response == null ? null : response.getData());
                if (response != null && response.hasError()) {
                    Log.errorf("Error executing GraphQL query for repository %s: %s",
                            evt.installationId(), response.getErrors());
                    errors.addAll(response.getErrors());
                }
            } catch (ExecutionException | InterruptedException e) {
                Log.errorf(e, "[%s] Error executing GraphQL query: %s",
                        evt.installationId(), e.toString());
                exceptions.add(e);
            }
            return response;
        }

        /**
         * Exceptions and errors are captured for caller in the queryContext
         *
         * @param query GraphQL query. Values for owner and name
         *        ({@code $name: String!, $owner: String!})
         *        will be provided
         * @return GraphQL Response
         * @see #execRepoQuerySync(String, Map)
         */
        public Response execRepoQuerySync(String query) {
            return execRepoQuerySync(query, new HashMap<>());
        }

        /**
         * Exceptions and errors are captured for caller in the queryContext
         *
         * @param query GraphQL query. Values for repository owner and name
         *        ({@code $name: String!, $owner: String!})
         *        will be provided
         * @return GraphQL Response
         * @see CFGHQueryHelper#execQuerySync(String, Map)
         */
        public Response execRepoQuerySync(String query, Map<String, Object> variables) {
            variables.putIfAbsent("owner", evt.getRepoOwner());
            variables.putIfAbsent("name", evt.getRepoName());
            return execQuerySync(query, variables);
        }

        public <T> T getCache(String itemId, String key, Class<T> type) {
            return QueryHelper.getCache(itemId, key, type);
        }

        public void putCache(String itemId, String key, Object value) {
            QueryHelper.putCache(itemId, key, value);
        }

        /**
         * Get labels for the labelable item
         *
         * @param cacheId Labelable item node id
         * @return collection of labels for the item
         */
        public Collection<DataLabel> getCachedLabels(String itemId) {
            Collection<DataLabel> labels = getCache(itemId, "labels", Collection.class);
            if (labels != null) {
                return labels;
            }
            // fresh fetch graphQL fetch
            labels = DataLabel.queryLabels(this, itemId);
            if (labels != null) {
                putCache(itemId, LABEL, labels);
            }
            return labels;
        }

        /**
         * Add a label from the list of known labels if the associated item has been seen/cached
         *
         * @param cacheId Labelable item node id
         * @param label Label to add
         * @return updated collection of labels for the item, or null if not cached
         */
        public Collection<DataLabel> addCachedLabel(String cacheId, DataLabel label) {
            Collection<DataLabel> labels = getCachedLabels(cacheId); // ensure labels are cached (if not already)
            if (labels == null) {
                return null;
            }
            labels.add(label);
            return labels;
        }

        /**
         * Remove a label from the list of known labels if the associated item exists
         *
         * @param cacheId Labelable item node id
         * @param label Label to remove
         * @return updated collection of labels for the item, or null if not cached
         */
        public Collection<DataLabel> removeCachedLabel(String cacheId, DataLabel label) {
            Collection<DataLabel> labels = getCachedLabels(cacheId); // ensure labels are cached (if not already)
            if (labels == null) {
                return null;
            }
            labels.remove(label);
            return labels;
        }

        /**
         * Update a label in the cache if the associated item exists
         *
         * @param cacheId Labelable item node id
         * @param label Label to update/replace
         * @return updated collection of labels for the item, or null if not cached
         */
        public Collection<DataLabel> updateCachedLabel(String cacheId, DataLabel label) {
            Collection<DataLabel> labels = getCachedLabels(cacheId); // ensure labels are cached (if not already)
            if (labels == null) {
                return null;
            }
            // only the id is equals/hashcode -- so remove and add
            labels.remove(label);
            labels.add(label);
            return labels;
        }

        /**
         * Add a label from the list of known labels if the associated item has been seen/cached
         *
         * @param cacheId Labelable item node id
         * @param labels updated Set of labels (e.g. post modify command)
         * @return updated collection of labels for the item, or null if not cached
         */
        public Collection<DataLabel> modifyLabels(String cacheId, List<DataLabel> labels) {
            Set<DataLabel> newLabels = DataLabel.modifyLabels(this, cacheId, labels);
            if (newLabels == null) {
                return null;
            }
            QueryHelper.putCache(cacheId, LABEL, newLabels);
            return newLabels;
        }
    }
}
