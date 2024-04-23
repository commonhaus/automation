package org.commonhaus.automation.github.model;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.JsonObject;

import org.commonhaus.automation.AppConfig;
import org.commonhaus.automation.github.EventData;
import org.commonhaus.automation.github.voting.VoteEvent;
import org.kohsuke.github.GHIOException;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.ReactionContent;

import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkus.logging.Log;
import io.smallrye.graphql.client.GraphQLError;
import io.smallrye.graphql.client.Response;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

@ApplicationScoped
public class QueryHelper {

    private final AppConfig botConfig;
    private final GitHubClientProvider gitHubClientProvider;
    private String botSenderLogin;

    public QueryHelper(AppConfig botConfig, GitHubClientProvider gitHubClientProvider) {
        this.botConfig = botConfig;
        this.gitHubClientProvider = gitHubClientProvider;
    }

    public EventQueryContext newQueryContext(EventData event) {
        return new EventQueryContext(this, botConfig, gitHubClientProvider, event);
    }

    public EventQueryContext newQueryContext(EventData eventData, GitHub github, DynamicGraphQLClient graphQLClient) {
        return newQueryContext(eventData)
                .addExisting(github)
                .addExisting(graphQLClient);
    }

    public ScheduledQueryContext newScheduledQueryContext(GHRepository repository, long installationId) {
        return new ScheduledQueryContext(this, botConfig, gitHubClientProvider, repository, installationId);
    }

    public ScheduledQueryContext newScheduledQueryContext(ScheduledQueryContext ctx, EventType eventType) {
        return new ScheduledQueryContext(ctx, eventType)
                .addExisting(ctx.github)
                .addExisting(ctx.graphQLClient);
    }

    /** Package private. */
    String getBotSenderLogin() {
        return botSenderLogin;
    }

    /** Package private. Not synchronized or volatile, eventual consistency is fine */
    void setBotSenderLogin(String login) {
        botSenderLogin = login;
    }

    @FunctionalInterface
    public interface GitHubParameterApiCall<R> {
        R apply(GitHub gh, boolean isDryRun) throws IOException;
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
    public static abstract class QueryContext {
        protected final QueryHelper helper;
        protected final AppConfig botConfig;

        protected final GitHubClientProvider gitHubClientProvider;

        protected final List<GraphQLError> errors = new ArrayList<>(1);
        protected final List<Throwable> exceptions = new ArrayList<>(1);

        /** Short-lived (minutes) CLI for GitHub REST API */
        protected GitHub github;
        /** Short-lived (minutes) CLI for GitHub GraphQL API */
        protected DynamicGraphQLClient graphQLClient;

        QueryContext(QueryHelper helper, AppConfig botConfig, GitHubClientProvider gitHubClientProvider) {
            this.helper = helper;
            this.botConfig = botConfig;
            this.gitHubClientProvider = gitHubClientProvider;
        }

        public abstract String getLogId();

        public abstract long installationId();

        public abstract String getRepositoryId();

        public abstract GHRepository getRepository();

        public abstract GHOrganization getOrganization();

        public abstract EventType getEventType();

        public boolean isCredentialValid() {
            return github != null && github.isCredentialValid();
        }

        public boolean reauthenticate() {
            github = gitHubClientProvider.getInstallationClient(installationId());
            graphQLClient = null;
            return isCredentialValid();
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
                graphQLClient = gitHubClientProvider.getInstallationGraphQLClient(installationId());
            }
            return graphQLClient;
        }

        /**
         * Get GitHub instance for REST API; access should be via subclass (DryRun,
         * logging)
         */
        public GitHub getGitHub() {
            if (github == null) {
                github = gitHubClientProvider.getInstallationClient(installationId());
            }
            return github;
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
                Log.errorf(e, "[%s] Error making GH Request: %s", getLogId(), e.toString());
                if (Log.isDebugEnabled() && e.getResponseHeaderFields() != null) {
                    e.getResponseHeaderFields()
                            .forEach((k, v) -> Log.debugf("%s: %s", k, v));
                }
                addException(e);
            } catch (IOException | RuntimeException e) {
                Log.errorf(e, "[%s] Error making GH Request: %s", getLogId(), e.toString());
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
         * @param query GraphQL query. Values for repository owner and name
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
         * @param variables Additional query variables
         * @return GraphQL Response
         * @see #execQuerySync(String, Map)
         */
        public Response execRepoQuerySync(String query, Map<String, Object> variables) {
            GHRepository repo = getRepository();
            variables.putIfAbsent("owner", repo.getOwnerName());
            variables.putIfAbsent("name", repo.getName());
            return execQuerySync(query, variables);
        }

        /**
         * Get labels for the labelable item or repository
         *
         * @param itemId node id (issue, pull request, discussion or repository)
         * @return collection of labels for the item
         */
        public Collection<DataLabel> getLabels(String itemId) {
            Set<DataLabel> labels = QueryCache.LABELS.getCachedValue(itemId);
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
        public Collection<DataLabel> findLabels(List<String> labels) {
            Collection<DataLabel> repoLabels = getLabels(getRepositoryId());
            if (repoLabels == null) {
                Log.errorf("[%s] Labels not found in repository %s", getLogId(), getRepositoryId());
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
         * @param nodeId Labelable item node id
         * @param label Label to add
         * @param action Type of action to perform (created, edited, deleted, labeled, unlabeled)
         */
        public void modifyLabels(String nodeId, DataLabel label, ActionType action) {
            QueryCache.LABELS.computeIfPresent(nodeId, (k, v) -> {
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
         * @param nodeId Id of node(discussion or pull request or issue) to add labels to
         * @param label label name or id
         * @return updated collection of labels for the item, or null if no labels were found
         */
        public Collection<DataLabel> addLabel(String nodeId, String label) {
            return addLabels(nodeId, List.of(label));
        }

        /**
         * Add label by name or id to event item
         *
         * @param nodeId Id of node(discussion or pull request or issue) to add labels to
         * @param labels Collection of Label names or ids
         * @return updated collection of labels for the item, or null if no labels were found
         */
        public Collection<DataLabel> addLabels(String nodeId, List<String> labels) {
            Collection<DataLabel> newLabels = findLabels(labels);
            if (!newLabels.isEmpty()) {
                Set<DataLabel> currentLabels = DataLabel.addLabels(this, nodeId, newLabels);
                QueryCache.LABELS.putCachedValue(nodeId, currentLabels);
                return currentLabels;
            }
            return null;
        }

        public Collection<DataLabel> removeLabels(String nodeId, List<String> labels) {
            Collection<DataLabel> oldLabels = findLabels(labels);
            if (!oldLabels.isEmpty()) {
                Set<DataLabel> currentLabels = DataLabel.removeLabels(this, nodeId, oldLabels);
                QueryCache.LABELS.putCachedValue(nodeId, currentLabels);
                return currentLabels;
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

        String botLogin() {
            String login = helper.getBotSenderLogin();
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
                login = JsonAttribute.login.stringFrom(viewer);
                helper.setBotSenderLogin(login);
            }
            return login;
        }

        public void addBotReaction(String nodeId, ReactionContent reaction) {
            DataReaction.addBotReaction(this, nodeId, reaction);
        }

        public void removeBotReaction(String nodeId, ReactionContent reaction) {
            DataReaction.removeBotReaction(this, nodeId, reaction);
        }

        public BotComment updateBotComment(VoteEvent voteEvent, String commentBody) {
            return updateBotComment(voteEvent.commentPattern(), voteEvent.getEventType(), voteEvent.getId(), commentBody,
                    voteEvent.getBody());
        }

        BotComment updateBotComment(Pattern botCommentPattern, EventType eventType, String itemId, String commentBody,
                String itemBody) {
            if (hasErrors()) {
                Log.debugf("[%s] updateBotComment skipping due to errors", getLogId());
                return null;
            }

            BotComment botComment = QueryCache.RECENT_BOT_CONTENT.getCachedValue(itemId);
            DataCommonComment comment = null;
            if (botComment == null) {
                String commentId = BotComment.getCommentId(botCommentPattern, itemBody);
                comment = DataCommonComment.findBotComment(this, itemId, commentId);
                botComment = comment == null ? null : new BotComment(botCommentPattern, itemId, comment);
            }

            if (comment == null && botComment == null) {
                if (isDryRun()) {
                    // Create a fake comment based on one in commonhaus/automation-test
                    botComment = new BotComment(botCommentPattern, itemId).setBodyString(commentBody);
                } else {
                    // new comment
                    comment = switch (eventType) {
                        case discussion, discussion_comment ->
                            DataDiscussionComment.addComment(this, itemId, commentBody);
                        case issue, pull_request, issue_comment, pull_request_review ->
                            DataIssueComment.addIssueComment(this, itemId, commentBody);
                        default -> {
                            Log.errorf("[%s] addBotComment: Unknown event type", getLogId());
                            yield null;
                        }
                    };
                    botComment = comment == null ? null : new BotComment(botCommentPattern, itemId, comment);
                }
            } else if (botComment.requiresUpdate(commentBody)) {
                if (isDryRun()) {
                    Log.debugf("[%s] updateBotComment would edit comment %s with %s", getLogId(), botComment.getCommentId(),
                            commentBody);
                    botComment.setBodyString(commentBody);
                } else {
                    comment = switch (eventType) {
                        case discussion, discussion_comment ->
                            DataDiscussionComment.editComment(this, botComment.getCommentId(), commentBody);
                        case issue, pull_request, issue_comment, pull_request_review ->
                            DataIssueComment.editIssueComment(this, botComment.getCommentId(), commentBody);
                        default -> {
                            Log.errorf("[%s] updateItemDescription: Unknown event type", getLogId());
                            yield null;
                        }
                    };
                    // if an error happened, comment will be null, we should clear the cache
                    botComment = comment == null ? null : botComment.setBodyString(commentBody);
                }
            } else {
                Log.debugf("[%s] updateBotComment: comment %s unchanged", getLogId(), botComment.getCommentId());
            }
            QueryCache.RECENT_BOT_CONTENT.putCachedValue(itemId, botComment);
            return botComment;
        }

        public void updateItemDescription(EventType eventType, String nodeId, String bodyString) {
            if (isDryRun()) {
                Log.debugf("[%s] updateItemDescription would set body to: %s", getLogId(), bodyString);
                return;
            }
            if (hasErrors()) {
                Log.debugf("[%s] updateItemDescription skipping due to errors", getLogId());
                return;
            }

            switch (eventType) {
                case discussion, discussion_comment ->
                    DataDiscussion.editDiscussion(this, nodeId, bodyString);
                case issue, issue_comment ->
                    DataCommonItem.editIssueDescription(this, nodeId, bodyString);
                case pull_request, pull_request_review ->
                    DataCommonItem.editPullRequestDescription(this, nodeId, bodyString);
                default -> Log.errorf("[%s] updateItemDescription: Unknown event type", getLogId());
            }
        }

        public List<DataReaction> getReactions(String nodeId) {
            if (hasErrors()) {
                return List.of();
            }
            return DataReaction.queryReactions(this, nodeId);
        }

        public List<DataCommonComment> getComments(String nodeId) {
            if (hasErrors()) {
                return List.of();
            }
            return DataCommonComment.queryComments(this, nodeId);
        }
    }

    public static class BotComment {

        final Pattern botCommentPattern;
        final private String itemId;
        private String id;
        private int databaseId;
        private String url;
        private String bodyString;

        BotComment(Pattern botCommentPattern, String itemId, DataCommonComment comment) {
            this.botCommentPattern = botCommentPattern;
            this.itemId = itemId;
            this.id = comment.id;
            this.databaseId = comment.databaseId;
            this.url = comment.url;
            this.bodyString = comment.body;
        }

        // For dry run: this comment exists (in test repo), but probably not on the original item
        BotComment(Pattern botCommentPattern, String itemId) {
            this.botCommentPattern = botCommentPattern;
            this.itemId = itemId;
            this.id = "DC_kwDOLDuJqs4AfJV4";
            this.databaseId = 8164728;
            this.url = "https://github.com/commonhaus/automation-test/discussions/6#discussioncomment-8164728";
            this.bodyString = "";
        }

        public String getItemId() {
            return itemId;
        }

        public String getCommentId() {
            return id;
        }

        public int getCommentDatabaseId() {
            return databaseId;
        }

        public String getUrl() {
            return url;
        }

        public String getBodyString() {
            return bodyString;
        }

        BotComment setBodyString(String bodyString) {
            this.bodyString = bodyString;
            return this;
        }

        public boolean requiresUpdate(String bodyString) {
            return !this.bodyString.equals(bodyString);
        }

        public String markdownLink(String linkText) {
            return String.format("[%s](%s \"%s\")", linkText, url, id);
        }

        public static String getCommentId(Pattern botCommentPattern, String itemBody) {
            Matcher matcher = botCommentPattern.matcher(itemBody);
            if (matcher.find()) {
                if (matcher.group(2) != null) {
                    return matcher.group(2);
                }
                int lastDash = matcher.group(1).lastIndexOf("-");
                return matcher.group(1).substring(lastDash + 1);
            }
            return null;
        }
    }
}
