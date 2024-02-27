package org.commonhaus.automation.github.model;

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
import jakarta.json.JsonObject;

import org.commonhaus.automation.AppConfig;
import org.commonhaus.automation.github.EventData;
import org.commonhaus.automation.github.model.EventPayload.DiscussionPayload;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHIOException;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.ReactionContent;

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
                .build()),
        RECENT_BOT_CONTENT(Caffeine.newBuilder()
                .expireAfterWrite(6, TimeUnit.HOURS)
                .build()),
        TEAM(Caffeine.newBuilder()
                .expireAfterWrite(6, TimeUnit.HOURS)
                .build()),
        TEAM_LIST(Caffeine.newBuilder()
                .expireAfterWrite(12, TimeUnit.HOURS)
                .build());

        private final Cache<String, Object> cache;

        private QueryCache(Cache<String, Object> cache) {
            this.cache = cache;
        }

        @SuppressWarnings("unchecked")
        public <T> T getCachedValue(String key, Class<T> type) {
            T result = (T) cache.getIfPresent(key);
            if (result != null) {
                Log.debugf(":: HIT %s/%s ::: ", this.name(), key);
            } else {
                Log.debugf(":: MISS %s/%s ::: ", this.name(), key);
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
            Log.debugf(":: PUT %s/%s ::: ", this.name(), key);
            cache.put(key, value);
            return value;
        }

        @SuppressWarnings("unchecked")
        public <T> T computeIfPresent(String key, BiFunction<String, Object, T> mappingFunction) {
            Log.debugf(":: UPDATE %s/%s ::: ", this.name(), key);
            Object value = cache.asMap().computeIfPresent(key, mappingFunction);
            return (T) value;
        }

        public void invalidate(String key) {
            Log.debugf(":: INVALIDATE %s/%s ::: ", this.name(), key);
            cache.invalidate(key);
        }

        public void invalidateAll() {
            Log.debugf(":: INVALIDATE ALL %s ::: ", this.name());
            cache.invalidateAll();
        }
    }

    private final AppConfig botConfig;
    private final GitHubClientProvider gitHubClientProvider;
    private String botSenderLogin;

    public QueryHelper(AppConfig botConfig, GitHubClientProvider gitHubClientProvider) {
        this.botConfig = botConfig;
        this.gitHubClientProvider = gitHubClientProvider;
    }

    public QueryContext newQueryContext(EventData event) {
        return new QueryContext(this, botConfig, gitHubClientProvider, event);
    }

    public QueryContext newQueryContext(EventData eventData, GitHub github) {
        return newQueryContext(eventData).addExisting(github);
    }

    /** Package private. Mostly for test */
    void setBotSenderLogin(String login) {
        botSenderLogin = login;
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
        private final QueryHelper helper;
        private final AppConfig botConfig;

        private final GitHubClientProvider gitHubClientProvider;

        protected final List<GraphQLError> errors = new ArrayList<>(1);
        protected final List<Throwable> exceptions = new ArrayList<>(1);

        /** Short-lived (minutes) CLI for GitHub REST API */
        private GitHub github;
        /** Short-lived (minutes) CLI for GitHub GraphQL API */
        private DynamicGraphQLClient graphQLClient;
        /** Event data used to construct this query context */
        private final EventData evt;

        private QueryContext(QueryHelper helper, AppConfig botConfig, GitHubClientProvider gitHubClientProvider,
                EventData event) {
            this.helper = helper;
            this.botConfig = botConfig;
            this.gitHubClientProvider = gitHubClientProvider;

            // unpack Json-B from string
            this.evt = event;
        }

        public boolean isCredentialValid() {
            return github != null && github.isCredentialValid();
        }

        public EventData getEventData() {
            return evt;
        }

        public String getLogId() {
            return evt.getLogId();
        }

        public boolean hasErrors() {
            return !errors.isEmpty() || !exceptions.isEmpty();
        }

        public boolean hasNotFound() {
            return exceptions.isEmpty()
                    && errors.size() == 1
                    && errors.stream().anyMatch(e -> e.getOtherFields().containsKey("type")
                            && e.getOtherFields().get("type").equals("NOT_FOUND"));
        }

        public void clearErrors() {
            errors.clear();
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
        public GitHub getGitHub() {
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
                Log.errorf(e, "[%s] Error making GH Request: %s", evt.getLogId(), e.toString());
                if (Log.isDebugEnabled()) {
                    e.getResponseHeaderFields().forEach((k, v) -> Log.debugf("%s: %s", k, v));
                }
                addException(e);
            } catch (IOException | RuntimeException e) {
                Log.errorf(e, "[%s] Error making GH Request: %s", evt.getLogId(), e.toString());
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
                Log.infof("[%s] DryRun would execute the following query :: %s :: with attributes :: %s",
                        getLogId(), query, variables);
                return null;
            }

            DynamicGraphQLClient graphqlCLI = getGraphQLClient();
            Response response = null;
            try {
                Log.debugf("[%s] execQuerySync: %s with %s", getLogId(), variables, query);
                response = graphqlCLI.executeSync(query, variables);
                Log.debugf("[%s] execQuerySync: result ? %s", getLogId(), response == null ? null : response.getData());
                if (response != null && response.hasError()) {
                    Log.errorf("[%s] Error executing GraphQL query: %s",
                            getLogId(), response.getErrors());
                    errors.addAll(response.getErrors());
                }
            } catch (ExecutionException | InterruptedException | RuntimeException e) {
                Log.errorf(e, "[%s] Error executing GraphQL query: %s",
                        getLogId(), e.toString());
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
         * @see #execQuerySync(String, Map)
         */
        public Response execRepoQuerySync(String query, Map<String, Object> variables) {
            variables.putIfAbsent("owner", evt.getRepoOwner());
            variables.putIfAbsent("name", evt.getRepoName());
            return execQuerySync(query, variables);
        }

        /**
         * Get labels for the labelable item or repository
         *
         * @param itemId node id (issue, pull request, discussion or repository)
         * @return collection of labels for the item
         */
        public Collection<DataLabel> getLabels(String itemId) {
            @SuppressWarnings("unchecked")
            Set<DataLabel> labels = QueryCache.LABELS.getCachedValue(itemId, Set.class);
            if (labels != null) {
                return labels;
            }
            // fresh fetch graphQL fetch
            labels = DataLabel.queryLabels(this, itemId);
            if (labels != null) {
                QueryCache.LABELS.putCachedValue(itemId, labels);
            }
            return labels;
        }

        /**
         * Find repository labels matching names or ids
         *
         * @param labels List of label names or ids
         * @return collection of resolved labels
         */
        public Collection<DataLabel> findLabels(EventData eventData, List<String> labels) {
            Collection<DataLabel> repoLabels = getLabels(eventData.getRepositoryId());
            if (repoLabels == null) {
                Log.errorf("[%s] Labels not found in repository", getLogId());
                return List.of();
            }

            List<DataLabel> newLabels = new ArrayList<>();
            // Find the repository label (with id) for each requested label
            for (String labelName : labels) {
                // Find the matching label in repository labels
                DataLabel label = repoLabels.stream()
                        .filter(l -> l.name.equalsIgnoreCase(labelName) || l.id.equals(labelName))
                        .findFirst().orElse(null);

                if (label == null) {
                    Log.errorf("[%s] Label '%s' not found in repository", getLogId(), labelName);
                } else {
                    newLabels.add(label);
                }
            }
            return newLabels;
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
        public void modifyLabels(String cacheId, DataLabel label, ActionType action) {
            QueryCache.LABELS.computeIfPresent(cacheId, (k, v) -> {
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
         * Add label by name or id to event item
         *
         * @param eventData EventData
         * @param label Label name or id
         * @return updated collection of labels for the item, or null if label not found
         */
        public Collection<DataLabel> addLabel(EventData eventData, String label) {
            return addLabels(eventData, List.of(label));
        }

        /**
         * Add label by name or id to event item
         *
         * @param eventData EventData
         * @param labels Collection of Label names or ids
         * @return updated collection of labels for the item, or null if no labels were found
         */
        public Collection<DataLabel> addLabels(EventData eventData, List<String> labels) {
            String nodeId = eventData.getNodeId();
            Collection<DataLabel> newLabels = findLabels(eventData, labels);
            if (!newLabels.isEmpty()) {
                Set<DataLabel> currentLabels = DataLabel.addLabels(this, nodeId, newLabels);
                if (currentLabels != null) {
                    QueryCache.LABELS.putCachedValue(nodeId, currentLabels);
                    return currentLabels;
                }
            }
            return null;
        }

        public boolean isDryRun() {
            return botConfig.isDryRun();
        }

        public boolean isBot(String login) {
            String botLogin = botLogin();
            return botLogin.equals(login) || botLogin.replace("[bot]", "").equals(login);
        }

        private String botLogin() {
            String login = helper.botSenderLogin;
            if (login == null) {
                Response response = execQuerySync("""
                            query {
                                viewer {
                                login
                                }
                            }
                        """, new HashMap<>());
                if (response.hasError()) {
                    return "unknown";
                }
                JsonObject viewer = JsonAttribute.viewer.jsonObjectFrom(response.getData());
                helper.botSenderLogin = login = JsonAttribute.login.stringFrom(viewer);
            }
            return login;
        }

        public void addBotReaction(ReactionContent reaction) {
            DataReaction.addBotReaction(this, evt.getNodeId(), reaction);
        }

        public void removeBotReaction(ReactionContent reaction) {
            DataReaction.removeBotReaction(this, evt.getNodeId(), reaction);
        }

        public DataCommonComment updateBotComment(String commentBody, Integer commentIdFromBody) {
            if (hasErrors()) {
                Log.debugf("[%s] updateBotComment skipping due to errors", getLogId());
                return null;
            }

            String itemId = this.evt.getNodeId();

            DataCommonComment comment = QueryCache.RECENT_BOT_CONTENT.getCachedValue(itemId, DataCommonComment.class);
            if (comment == null) {
                comment = DataCommonComment.findBotComment(this, itemId, commentIdFromBody);
            }

            if (comment == null) {
                // new comment
                comment = switch (evt.getEventType()) {
                    case discussion, discussion_comment -> {
                        yield DataDiscussionComment.addComment(this, itemId, commentBody);
                    }
                    case issue, pull_request, issue_comment -> {
                        yield DataIssueComment.addIssueComment(this, itemId, commentBody);
                    }
                    default -> {
                        Log.errorf("[%s] addBotComment: Unknown event type", getLogId());
                        yield null;
                    }
                };
            } else {
                if (isDryRun()) {
                    Log.debugf("[%s] updateBotComment would add comment: %s", getLogId(), commentBody);
                    comment.body = commentBody;
                    return comment;
                }
                comment = switch (evt.getEventType()) {
                    case discussion, discussion_comment -> {
                        yield DataDiscussionComment.editComment(this, comment, commentBody);
                    }
                    case issue, pull_request, issue_comment -> {
                        yield DataIssueComment.editIssueComment(this, comment, commentBody);
                    }
                    default -> {
                        Log.errorf("[%s] updateItemDescription: Unknown event type", getLogId());
                        yield null;
                    }
                };
            }
            // if we created or updated comment, update cache
            if (comment != null) {
                QueryCache.RECENT_BOT_CONTENT.putCachedValue(itemId, comment);
            }
            return comment;
        }

        public void updateItemDescription(String bodyString) {
            if (isDryRun()) {
                Log.debugf("[%s] updateItemDescription would set body to: %s", getLogId(), bodyString);
                return;
            }
            if (hasErrors()) {
                Log.debugf("[%s] updateItemDescription skipping due to errors", getLogId());
                return;
            }

            switch (evt.getEventType()) {
                case discussion, discussion_comment -> {
                    DiscussionPayload payload = evt.getEventPayload();
                    DataDiscussion.editDiscussion(this, payload.discussion, bodyString);
                }
                case issue -> {
                    GHEventPayload.Issue payload = evt.getGHEventPayload();
                    updateIssueDescription(payload.getIssue(), bodyString);
                }
                case issue_comment -> {
                    GHEventPayload.IssueComment payload = evt.getGHEventPayload();
                    updateIssueDescription(payload.getIssue(), bodyString);
                }
                case pull_request -> {
                    GHEventPayload.PullRequest payload = evt.getGHEventPayload();
                    updateIssueDescription(payload.getPullRequest(), bodyString);
                }
                default -> {
                    Log.errorf("[%s] updateItemDescription: Unknown event type", getLogId());
                }
            }
        }

        private void updateIssueDescription(GHIssue issue, String bodyString) {
            execGitHubSync((gh, dryRun) -> {
                issue.setBody(bodyString);
                return null;
            });
        }

        public List<DataReaction> getReactions() {
            if (hasErrors()) {
                return List.of();
            }
            return DataReaction.queryReactions(this, this.evt.getNodeId());
        }
    }
}
