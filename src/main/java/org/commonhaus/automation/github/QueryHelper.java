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
import java.util.function.BiFunction;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.commonhaus.automation.AppConfig;
import org.commonhaus.automation.github.model.ActionType;
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
    public enum QueryCache {
        LABELS(Caffeine.newBuilder()
                .expireAfterWrite(6, TimeUnit.HOURS)
                .build()),
        GLOB(Caffeine.newBuilder()
                .maximumSize(200)
                .build());

        private final Cache<String, Object> cache;

        private QueryCache(Cache<String, Object> cache) {
            this.cache = cache;
        }

        @SuppressWarnings("unchecked")
        public <T> T getCachedValue(String key, Class<T> type) {
            T result = (T) cache.getIfPresent(key);
            if (result != null) {
                Log.debugf(":: HIT :: %s/%s ::: ", this.name(), key);
            } else {
                Log.debugf(":: MISS :: %s/%s ::: ", this.name(), key);
            }
            return result;
        }

        /**
         * Put a value into the cache
         *
         * @param key
         * @param value to be cached
         * @return new value
         */
        public <T> T putCachedValue(String key, T value) {
            Log.debugf(":: PUT :: %s/%s ::: ", this.name(), key);
            cache.put(key, value);
            return value;
        }

        @SuppressWarnings("unchecked")
        public <T> T computeIfPresent(String key, BiFunction<String, Object, T> mappingFunction) {
            Log.debugf(":: UPDATE :: %s/%s ::: ", this.name(), key);
            Object value = cache.asMap().computeIfPresent(key, mappingFunction);
            return (T) value;
        }

        public void invalidate(String key) {
            Log.debugf(":: INVALIDATE :: %s/%s ::: ", this.name(), key);
            cache.invalidate(key);
        }

        public void invalidateAll() {
            Log.debugf(":: INVALIDATE ALL :: %s ::: ", this.name());
            cache.invalidateAll();
        }
    }

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
            R apply(GitHub gh, boolean isDryRun) throws GHIOException, IOException;
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
                return ghApiCall.apply(getGitHub(), botConfig.isDryRun());
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

        /**
         * Get labels for the labelable item
         *
         * @param cacheId Labelable item node id
         * @return collection of labels for the item
         */
        public Collection<DataLabel> getCachedLabels(String itemId) {
            @SuppressWarnings("unchecked")
            Set<DataLabel> labels = QueryHelper.QueryCache.LABELS.getCachedValue(itemId, Set.class);
            if (labels != null) {
                return labels;
            }
            // fresh fetch graphQL fetch
            labels = DataLabel.queryLabels(this, itemId);
            if (labels != null) {
                QueryHelper.QueryCache.LABELS.putCachedValue(itemId, labels);
            }
            return labels;
        }

        /**
         * Add, remove, or edit a label from the list of known labels
         * if the associated item has been seen/cached
         *
         * @param cacheId Labelable item node id
         * @param label Label to add
         * @param action Type of action to perform (created, edited, deleted, labeled, unlabeled)
         * @return updated collection of labels for the item, or null if not cached
         */
        public Collection<DataLabel> modifyLabels(String cacheId, DataLabel label, ActionType action) {
            return QueryHelper.QueryCache.LABELS.computeIfPresent(cacheId, (k, v) -> {
                @SuppressWarnings("unchecked")
                Set<DataLabel> labels = (Set<DataLabel>) v;
                if (action == ActionType.deleted || action == ActionType.unlabeled || action == ActionType.edited) {
                    labels.remove(label);
                }
                if (action == ActionType.created || action == ActionType.labeled || action == ActionType.edited) {
                    labels.add(label);
                }
                return labels;
            });
        }

        /**
         * Replace cached labels with result of modification (post-mutation)
         *
         * @param cacheId Labelable item node id
         * @param labels labels to add
         * @return updated Set of labels (e.g. post-mutation)
         */
        public Collection<DataLabel> addLabels(String cacheId, List<DataLabel> labels) {
            Set<DataLabel> newLabels = DataLabel.addLabels(this, cacheId, labels);
            if (newLabels == null) {
                return null;
            }
            return QueryHelper.QueryCache.LABELS.putCachedValue(cacheId, newLabels);
        }

        public boolean isDryRun() {
            return botConfig.isDryRun();
        }
    }
}
