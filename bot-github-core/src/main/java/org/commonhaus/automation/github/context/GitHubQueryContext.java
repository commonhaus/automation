package org.commonhaus.automation.github.context;

import static org.commonhaus.automation.github.context.BaseQueryCache.BOT_LOGIN;
import static org.commonhaus.automation.github.context.BaseQueryCache.LABELS;
import static org.commonhaus.automation.github.context.BaseQueryCache.RECENT_BOT_CONTENT;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import jakarta.json.JsonObject;

import org.commonhaus.automation.ContextService;
import org.commonhaus.automation.GraphQLQueryContext;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.HttpException;
import org.kohsuke.github.ReactionContent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.logging.Log;
import io.smallrye.graphql.client.Response;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

/**
 * Base class for short-lived context objects that can handle a series of GitHub
 * API calls and GraphQL queries for a single event.
 * <p>
 * It provides context for the duration of processing one event and aggregates
 * errors that occur during this processing for centralized handling.
 * <p>
 * This context is created by a {@link ContextService} implementation and should not
 * be retained beyond the scope of a single event processing.
 * <p>
 * Error handling in QueryContext is designed to collect errors during processing
 * to allow continuation where possible, with final error handling and/or reporting
 * performed by the caller.
 * <p>
 * This class is not thread-safe and should not be shared between threads.
 * It can be garbage collected after the event processing is complete.
 */
public class GitHubQueryContext extends GraphQLQueryContext {
    static final String QUERY_BOT_LOGIN = """
            query {
                viewer {
                login
                }
            }
            """.stripIndent();

    @FunctionalInterface
    public interface GitHubParameterApiCall<R> {
        R apply(GitHub gh, boolean isDryRun) throws IOException;
    }

    protected final long installationId;

    protected GitHub github;
    protected DynamicGraphQLClient graphQLClient;

    public GitHubQueryContext(ContextService contextService, long installationId) {
        super(contextService);
        this.installationId = installationId;
    }

    public GitHubQueryContext(GitHubQueryContext other) {
        super(other.ctx);
        this.installationId = other.installationId;
    }

    public String getLogId() {
        return "" + installationId;
    }

    public String getRepositoryId() {
        throw new UnsupportedOperationException("Unimplemented method 'getRepositoryId'");
    }

    public GHRepository getRepository() {
        throw new UnsupportedOperationException("Unimplemented method 'getRepository'");
    }

    public GHOrganization getOrganization() {
        throw new UnsupportedOperationException("Unimplemented method 'getOrganization'");
    }

    public EventType getEventType() {
        throw new UnsupportedOperationException("Unimplemented method 'getEventType'");
    }

    public ActionType getActionType() {
        throw new UnsupportedOperationException("Unimplemented method 'getActionType'");
    }

    public JsonObject getJsonData() {
        throw new UnsupportedOperationException("Unimplemented method 'getJsonData'");
    }

    /**
     * Use an existing GitHub client instance.
     * <p>
     * Useful when creating a new context from an existing one
     * (to isolate errors or exceptions, for example).
     *
     * @param github Existing GitHub instance
     * @return this context
     */
    public GitHubQueryContext withExisting(GitHub github) {
        this.github = github;
        return this;
    }

    /**
     * Get GitHub instance for REST API; access should be via subclass (DryRun,
     * logging)
     */
    public GitHub getGitHub() {
        if (github == null) {
            github = ctx.getInstallationClient(installationId);
        }
        return github;
    }

    /**
     * Use an existing GraphQL client instance.
     * <p>
     * Useful when creating a new context from an existing one
     * (to isolate errors or exceptions, for example).
     *
     * @param graphQLClient Existing DynamicGraphQLClient instance
     * @return this context
     */
    public GitHubQueryContext withExisting(DynamicGraphQLClient graphQLClient) {
        this.graphQLClient = graphQLClient;
        return this;
    }

    /**
     * Get GitHub instance for GraphQL API; access should be via helper (DryRun,
     * logging)
     */
    public DynamicGraphQLClient getGraphQLClient() {
        if (graphQLClient == null) {
            graphQLClient = ctx.getInstallationGraphQLClient(installationId);
        }
        return graphQLClient;
    }

    /**
     * The installationId is the unique identifier for the GitHub App installation
     * associated with this context. It is almost always set.
     * <p>
     * It is not set for Sponsorship events (REST API only).
     *
     * @return the installationId associated with this context
     */
    public long getInstallationId() {
        return installationId;
    }

    protected void cleanupAuthenticationError() {
        // Clear the cached clients to force fresh token acquisition
        BaseQueryCache.resetCachedClients(installationId);
        // Reset our local instances to force fresh retrieval
        this.github = null;
        this.graphQLClient = null;
    }

    /**
     * Invoke the specified function within a try/finally using the GitHub instance
     * held in this context.
     * <p>
     * The dryRun parameter is passed to the function.
     * <p>
     * Operations will not be attempted if there are errors present in the context
     * (connection issues, for example).
     * <p>
     * Some exceptions, specifically those related to connections with GitHub
     * will result in an email being sent to the configured error addresses.
     * <p>
     * Recoverable exceptions (like file not found errors) are captured and can be
     * checked for with {@link #hasNotFound()} or cleared with {@link #checkRemoveNotFound()}.
     * <p>
     * Errors and exceptions are captured for the caller in the queryContext,
     * and can be checked with {@link #hasErrors()},
     * bundled for final error handling with {@link #bundleExceptions()}
     * or cleared with {@link #clearErrors()}.
     *
     * @param <R> return type
     * @param ghApiCall Function to be invoked with GitHub instance
     * @return result of ghApiCall or null of there were errors
     * @see #hasNotFound()
     * @see #checkRemoveNotFound()
     * @see #hasErrors()
     * @see #bundleExceptions()
     * @see #clearErrors()
     */
    public <R> R execGitHubSync(GitHubParameterApiCall<R> ghApiCall) {
        if (hasErrors()) {
            Log.debugf("[%s] execGitHubSync: QueryContext has existing errors, skipping: %s",
                    getLogId(), bundleExceptions());
            return null;
        }
        try {
            return ghApiCall.apply(getGitHub(), isDryRun());
        } catch (GHFileNotFoundException e) {
            addException(e);
        } catch (HttpException he) {
            if (he.getResponseCode() == 401 || he.getResponseCode() == 403) {
                if (countAuthRetry++ < 2) {
                    Log.debugf("[%s] execGitHubSync: Authorization error: %s", getLogId(), he);
                    cleanupAuthenticationError();
                    // Let's try again
                    return execGitHubSync(ghApiCall);
                } else {
                    Log.debugf("[%s] execGitHubSync: Auth error retry limit reached", getLogId());
                }
            } else {
                Log.debugf("[%s] execGitHubSync: HttpException: %s", getLogId(), he);
            }
            addException(he);
        } catch (Throwable e) {
            Log.debugf("[%s] execGitHubSync: Throwable: %s", getLogId(), e);
            addException(e);
        }
        return null;
    }

    /**
     * Run a synchronous GraphQL query with standard repository parameters.
     * <p>
     * Overall behavior is similar to {@link #execQuerySync(String, Map)}
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
     * Run a synchronous GraphQL query with standard repository and other parameters.
     * <p>
     * Overall behavior is similar to {@link #execQuerySync(String, Map)}
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
     * Get the GitHub organization for the repository owner.
     * Note the wrapped call to {@link #execGitHubSync(GitHubParameterApiCall)},
     * which will capture errors and exceptions.
     * <p>
     * "Not found" is a normal status. That error will be cleared.
     * <p>
     * Note: GitHub caches user lookups
     *
     * @return
     */
    public GHUser getUser(String login) {
        return execGitHubSync((gh, dryRun) -> {
            GHUser user = gh.getUser(login);
            checkRemoveNotFound();
            return user;
        });
    }

    /**
     * Get the GitHub organization for the repository owner.
     * Note the wrapped call to {@link #execGitHubSync(GitHubParameterApiCall)},
     * which will capture errors and exceptions.
     * <p>
     * "Not found" is a normal status. That error will be cleared.
     *
     * @return
     */
    public GHRepository getRepository(String repoName) {
        return execGitHubSync((gh, dryRun) -> {
            GHRepository repo = gh.getRepository(repoName);
            checkRemoveNotFound();
            return repo;
        });
    }

    /**
     * Get the GitHub organization for the repository owner.
     * Note the wrapped call to {@link #execGitHubSync(GitHubParameterApiCall)},
     * which will capture errors and exceptions.
     * <p>
     * "Not found" is a normal status. That error will be cleared.
     * <p>
     * Note: GitHub caches org lookups
     *
     * @param orgName
     * @return
     */
    public GHOrganization getOrganization(String orgName) {
        return execGitHubSync((gh, dryRun) -> {
            GHOrganization org = gh.getOrganization(orgName);
            checkRemoveNotFound();
            return org;
        });
    }

    public boolean isBot(String login) {
        String botLogin = BOT_LOGIN.computeIfAbsent("" + getInstallationId(), k -> {
            Response response = execQuerySync(QUERY_BOT_LOGIN, new HashMap<>());
            if (hasErrors() || response == null) {
                return "unknown";
            }
            JsonObject viewer = JsonAttribute.viewer.jsonObjectFrom(response.getData());
            return JsonAttribute.login.stringFrom(viewer);
        });
        return botLogin.equals(login) || botLogin.replace("[bot]", "").equals(login);
    }

    /** Item-scoped comment lookup; doesn't always apply */
    public List<DataCommonComment> getCachedComments(String nodeId) {
        return null;
    }

    /** Item-scoped comment lookup; doesn't always apply */
    public void setCachedComments(String nodeId, List<DataCommonComment> comments) {
        // No-op
    }

    public List<DataCommonComment> getComments(String nodeId, Predicate<DataCommonComment> filter) {
        List<DataCommonComment> comments = getCachedComments(nodeId);
        if (comments == null) {
            if (hasErrors()) {
                return List.of();
            }
            comments = DataCommonComment.queryComments(this, nodeId);
            setCachedComments(nodeId, comments);
        }
        return comments.stream().filter(c -> filter.test(c)).toList();
    }

    public BotComment updateBotComment(Pattern botCommentPattern, EventType itemType,
            String itemId, String commentBody, String itemBody) {
        if (hasErrors()) {
            Log.debugf("[%s] updateBotComment skipping due to errors", getLogId());
            return null;
        }

        BotComment botComment = RECENT_BOT_CONTENT.get(itemId);
        DataCommonComment comment = null;
        if (botComment == null) {
            String commentId = BotComment.getCommentId(botCommentPattern, itemBody);
            comment = DataCommonComment.findComment(this, itemId, commentId);
            if (comment == null) {
                List<DataCommonComment> botComments = getComments(itemId, (c) -> isBot(c.author.login));
                if (botComments != null && !botComments.isEmpty()) {
                    comment = botComments.get(0);
                }
            }

            botComment = comment == null
                    ? null
                    : new BotComment(itemId, comment);
        }

        if (comment == null && botComment == null) {
            if (isDryRun()) {
                // Create a fake comment based on one in commonhaus/automation-test
                botComment = new BotComment(itemId).setBody(commentBody);
            } else {
                // new comment
                comment = switch (itemType) {
                    case discussion -> DataDiscussionComment.addComment(this, itemId, commentBody);
                    case issue, pull_request -> DataIssueComment.addIssueComment(this, itemId, commentBody);
                    default -> {
                        logAndSendEmail("addBotComment: Unknown event type " + itemType, null);
                        yield null;
                    }
                };
                botComment = comment == null
                        ? null
                        : new BotComment(itemId, comment).setBody(commentBody); // keep original body for test
            }
        } else if (botComment.requiresUpdate(commentBody)) {
            if (isDryRun()) {
                Log.debugf("[%s] updateBotComment would edit comment %s with %s", getLogId(), botComment.getCommentId(),
                        commentBody);
                botComment.setBody(commentBody);
            } else {
                comment = switch (itemType) {
                    case discussion -> DataDiscussionComment.editComment(this, botComment.getCommentId(), commentBody);
                    case issue, pull_request -> DataIssueComment.editIssueComment(this, botComment.getCommentId(), commentBody);
                    default -> {
                        logAndSendEmail("updateItemDescription: Unknown event type " + itemType, null);
                        yield null;
                    }
                };
                // if an error happened, comment will be null, we should clear the cache
                botComment = comment == null ? null : botComment.setBody(commentBody);
            }
        } else {
            Log.debugf("[%s] updateBotComment: comment %s unchanged", getLogId(), botComment.getCommentId());
        }
        RECENT_BOT_CONTENT.put(itemId, botComment);
        return botComment;
    }

    public DataCommonItem getItem(EventType eventType, String nodeId) {
        if (hasErrors()) {
            Log.debugf("[%s] getItem skipping due to errors", getLogId());
            return null;
        }
        return switch (eventType) {
            case discussion -> DataDiscussion.queryDiscussion(this, nodeId);
            case issue, pull_request -> DataCommonItem.queryItem(this, nodeId);
            default -> {
                logAndSendEmail("getItem: Unknown event type " + eventType, null);
                yield null;
            }
        };
    }

    public DataCommonItem createItem(EventType eventType, String title, String description, Collection<DataLabel> labels) {
        if (hasErrors()) {
            Log.debugf("[%s] createItem skipping due to errors", getLogId());
            return null;
        }
        return switch (eventType) {
            case issue -> DataCommonItem.createIssue(this, title, description, labels);
            default -> {
                logAndSendEmail("createItem: Unknown event type " + eventType, null);
                yield null;
            }
        };
    }

    public DataCommonItem updateItemDescription(EventType eventType, String nodeId, String bodyString) {
        return updateItemDescription(eventType, nodeId, bodyString, null);
    }

    public DataCommonItem updateItemDescription(EventType eventType, String nodeId, String bodyString, String fields) {
        if (isDryRun()) {
            Log.debugf("[%s] updateItemDescription would set body to: %s", getLogId(), bodyString);
            return null;
        }
        if (hasErrors()) {
            Log.debugf("[%s] updateItemDescription skipping due to errors", getLogId());
            return null;
        }

        return switch (eventType) {
            case discussion, discussion_comment -> DataDiscussion.editDiscussion(this, nodeId, bodyString, fields);
            case issue, issue_comment -> DataCommonItem.editIssueDescription(this, nodeId, bodyString, fields);
            case pull_request, pull_request_review -> DataCommonItem.editPullRequestDescription(this, nodeId, bodyString,
                    fields);
            default -> {
                logAndSendEmail("updateItemDescription: Unknown event type " + eventType, null);
                yield null;
            }
        };
    }

    public boolean closeIssue(GHIssue issue) {
        if (isDryRun()) {
            Log.debugf("[%s] closeIssue would close issue %s", getLogId(), issue.getNumber());
            return true;
        }
        execGitHubSync((gh, dryRun) -> {
            issue.close();
            return null;
        });
        if (hasErrors()) {
            checkRemoveNotFound();
            return false;
        }
        return true;
    }

    public List<DataDiscussion> findDiscussionsWithLabel(String label) {
        return DataDiscussion.findDiscussionsWithLabel(this, label);
    }

    public List<DataCommonItem> findIssuesWithLabel(String label) {
        return DataCommonItem.findIssuesWithLabel(this, label);
    }

    public List<DataPullRequestReview> queryReviews(String nodeId) {
        if (hasErrors()) {
            return List.of();
        }
        return DataPullRequestReview.queryReviews(this, nodeId);
    }

    public List<DataReaction> getReactions(String nodeId) {
        if (hasErrors()) {
            return List.of();
        }
        return DataReaction.queryReactions(this, nodeId);
    }

    public void addBotReaction(String nodeId, ReactionContent reaction) {
        DataReaction.addBotReaction(this, nodeId, reaction);
    }

    public void removeBotReaction(String nodeId, ReactionContent reaction) {
        DataReaction.removeBotReaction(this, nodeId, reaction);
    }

    /**
     * Get labels for the labelable item or repository
     *
     * @param itemId node id (issue, pull request, discussion or repository)
     * @return collection of labels for the item
     */
    public Collection<DataLabel> getLabels(String itemId) {
        Set<DataLabel> labels = LABELS.get(itemId);
        if (labels != null) {
            return labels;
        }
        // fresh fetch graphQL fetch
        labels = DataLabel.queryLabels(this, itemId);
        if (labels != null) {
            LABELS.put(itemId, labels);
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
            logAndSendEmail("[%s] Labels not found in repository %s".formatted(getLogId(), getRepositoryId()), null);
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
                logAndSendEmail("[%s] Label '%s' not found in repository".formatted(getLogId(), labelName), null);
            } else {
                newLabels.add(label);
            }
        }
        return newLabels;
    }

    /**
     * Add label by name or id to event item
     *
     * @param nodeId String node id of item (discussion or pull request or issue) to
     *        add labels to
     * @param label label name or label node id
     * @return updated collection of labels for the item, or null if no labels were
     *         found
     */
    public Collection<DataLabel> addLabel(String nodeId, String label) {
        return addLabels(nodeId, List.of(label));
    }

    /**
     * Add label by name or id to event item
     *
     * @param nodeId String node id or item (discussion or pull request or issue) to
     *        add labels to
     * @param labels Collection of Label names or ids
     * @return updated collection of labels for the item, or null if no labels were
     *         found
     */
    public Collection<DataLabel> addLabels(String nodeId, List<String> labels) {
        Collection<DataLabel> newLabels = findLabels(labels);
        if (!newLabels.isEmpty()) {
            Set<DataLabel> currentLabels = DataLabel.addLabels(this, nodeId, newLabels);
            LABELS.put(nodeId, currentLabels);
            return currentLabels;
        }
        return null;
    }

    /**
     * Add, remove, or edit a label from the list of known labels
     * if the associated item has been seen/cached
     *
     * @param nodeId Labelable item node id
     * @param label Label to add
     * @param action Type of action to perform (created, edited, deleted, labeled,
     *        unlabeled)
     */
    public void modifyLabels(String nodeId, DataLabel label, ActionType action) {
        LABELS.computeIfPresent(nodeId, (k, v) -> {
            @SuppressWarnings("unchecked")
            Set<DataLabel> labels = (Set<DataLabel>) v;
            if (action == ActionType.deleted || action == ActionType.unlabeled || action == ActionType.edited) {
                labels.remove(label);
            }
            if (action == ActionType.created || action == ActionType.labeled || action == ActionType.edited) {
                labels.add(label);
            }
            return labels.isEmpty() ? null : labels;
        });
    }

    public Collection<DataLabel> removeLabels(String nodeId, List<String> labels) {
        Collection<DataLabel> oldLabels = findLabels(labels);
        if (!oldLabels.isEmpty()) {
            Set<DataLabel> currentLabels = DataLabel.removeLabels(this, nodeId, oldLabels);
            LABELS.put(nodeId, currentLabels);
            return currentLabels;
        }
        return null;
    }

    public DataLabel createLabel(String labelName, String color) {
        return DataLabel.createLabel(this, this.getRepositoryId(), labelName, color);
    }

    public GHContent readSourceFile(GHRepository repo, String path) {
        GHContent content = execGitHubSync((gh, dryRun) -> repo.getFileContent(path));
        if (content == null || hasErrors()) {
            if (!checkRemoveNotFound()) {
                Log.debugf("readSourceFile: error reading source file %s in repo %s: %s", path, repo.getFullName(),
                        bundleExceptions());
            }
            return null;
        }
        return content;
    }

    public JsonNode readYamlContent(GHContent content) {
        try {
            return ctx.parseYamlFile(content);
        } catch (IOException e) {
            addException(e);
            return null;
        }
    }

    public <T> T readYamlContent(GHContent content, Class<T> type) {
        try {
            return ctx.parseYamlFile(content, type);
        } catch (IOException e) {
            addException(e);
            return null;
        }
    }

    public <T> T readYamlContent(GHContent content, TypeReference<T> type) {
        try {
            return ctx.parseYamlFile(content, type);
        } catch (IOException e) {
            addException(e);
            return null;
        }
    }

    public <T> String writeYamlValue(T user) {
        try {
            return ctx.writeYamlValue(user);
        } catch (IOException e) {
            addException(e);
            return null;
        }
    }

    public static String toOrganizationName(String fullName) {
        int pos = fullName.indexOf('/');
        return pos < 0 ? fullName : fullName.substring(0, pos);
    }

    public static String toRelativeName(String orgName, String fullName) {
        return fullName.replace(orgName + "/", "");
    }

    public static String toFullName(String orgName, String relativeName) {
        return orgName + "/" + relativeName;
    }
}
