package org.commonhaus.automation.github.context;

import static org.commonhaus.automation.github.context.BaseQueryCache.BOT_LOGIN;
import static org.commonhaus.automation.github.context.BaseQueryCache.LABELS;
import static org.commonhaus.automation.github.context.BaseQueryCache.RECENT_BOT_CONTENT;
import static org.commonhaus.automation.github.context.BaseQueryCache.TEAM_MEMBERS;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import jakarta.json.JsonObject;

import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHIOException;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTeam;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.HttpException;
import org.kohsuke.github.ReactionContent;

import io.quarkus.logging.Log;
import io.smallrye.graphql.client.GraphQLError;
import io.smallrye.graphql.client.Response;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public abstract class QueryContext {
    @FunctionalInterface
    public interface GitHubParameterApiCall<R> {
        R apply(GitHub gh, boolean isDryRun) throws IOException;
    }

    final List<GraphQLError> errors = new ArrayList<>(1);
    final List<Throwable> exceptions = new ArrayList<>(1);

    protected final ContextService ctx;
    protected final long installationId;

    protected GitHub github;
    protected DynamicGraphQLClient graphQLClient;

    protected QueryContext(ContextService contextService, long installationId) {
        this.ctx = contextService;
        this.installationId = installationId;
    }

    protected QueryContext(QueryContext other) {
        this.ctx = other.ctx;
        this.installationId = other.installationId;
    }

    public abstract String getLogId();

    public abstract String getRepositoryId();

    public abstract GHRepository getRepository();

    public abstract GHOrganization getOrganization();

    public abstract EventType getEventType();

    public abstract ActionType getActionType();

    public JsonObject getJsonData() {
        return null;
    }

    public boolean isDryRun() {
        return ctx.isDryRun();
    }

    public QueryContext addExisting(GitHub github) {
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

    public QueryContext addExisting(DynamicGraphQLClient graphQLClient) {
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

    public long getInstallationId() {
        return installationId;
    }

    public boolean checkExpiredConnection() {
        if (github == null) {
            github = ctx.getInstallationClient(getInstallationId());
            graphQLClient = null;
        }
        boolean result = execGitHubSync((gh, dryRun) -> {
            return !gh.isCredentialValid() || gh.isAnonymous();
        });
        clearErrors();
        return result;
    }

    public void addException(Exception e) {
        exceptions.add(e);
    }

    public boolean hasErrors() {
        return !errors.isEmpty() || !exceptions.isEmpty();
    }

    public void clearErrors() {
        errors.clear();
        exceptions.clear();
    }

    public Throwable bundleExceptions() {
        if (exceptions.size() == 1) {
            return exceptions.get(0);
        }
        return hasErrors()
                ? new PackagedException(exceptions, errors)
                : null;
    }

    public boolean hasNotFound() {
        return exceptions.stream().anyMatch(e -> e instanceof GHFileNotFoundException)
                || errors.stream().anyMatch(e -> e.getOtherFields().containsKey("type")
                        && e.getOtherFields().get("type").equals("NOT_FOUND"));
    }

    public boolean clearNotFound() {
        if (hasNotFound()) {
            clearErrors();
            return true;
        }
        return false;
    }

    public boolean hasConflict() {
        return exceptions.stream().anyMatch(e -> e instanceof HttpException
                && ((HttpException) e).getResponseCode() == 409);
    }

    public HttpException getConflict() {
        return exceptions.stream()
                .filter(e -> e instanceof HttpException
                        && ((HttpException) e).getResponseCode() == 409)
                .findFirst()
                .map(e -> (HttpException) e).orElse(null);
    }

    public boolean clearConflict() {
        if (hasConflict()) {
            clearErrors();
            return true;
        }
        return false;
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
            return ghApiCall.apply(getGitHub(), isDryRun());
        } catch (GHFileNotFoundException e) {
            addException(e);
        } catch (GHIOException e) {
            logAndSendEmail(getLogId(), "Error making GH Request", e);
            addException(e);
        } catch (IOException | RuntimeException e) {
            logAndSendEmail(getLogId(), "Error making GH Request", e);
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
        if (isDryRun() && query.contains("mutation")) {
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
                logAndSendEmail(getLogId(), "Error executing GraphQL query", response.getErrors().toString(),
                        null);
                errors.addAll(response.getErrors());
            }
        } catch (ExecutionException | InterruptedException | RuntimeException e) {
            logAndSendEmail(getLogId(), "Error executing GraphQL query", e);
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

    public GHUser getUser(String login) {
        return execGitHubSync((gh, dryRun) -> {
            GHUser user = gh.getUser(login);
            clearNotFound();
            return user;
        });
    }

    public GHRepository getRepository(String repoName) {
        return execGitHubSync((gh, dryRun) -> {
            GHRepository repo = gh.getRepository(repoName);
            clearNotFound();
            return repo;
        });
    }

    public GHOrganization getOrganization(String orgName) {
        return execGitHubSync((gh, dryRun) -> {
            GHOrganization org = gh.getOrganization(orgName);
            clearNotFound();
            return org;
        });
    }

    public GHTeam getTeam(GHOrganization org, String relativeName) {
        return execGitHubSync((gh, dryRun) -> {
            GHTeam team = org.getTeamByName(relativeName);
            clearNotFound();
            return team;
        });
    }

    public boolean isBot(String login) {
        String botLogin = BOT_LOGIN.computeIfAbsent("" + getInstallationId(), k -> {
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
            return JsonAttribute.login.stringFrom(viewer);
        });
        return botLogin.equals(login) || botLogin.replace("[bot]", "").equals(login);
    }

    public boolean isLoginIncluded(String login, List<String> groups) {
        if (groups == null) {
            return true;
        } else if (groups.isEmpty()) {
            return false;
        }
        for (var g : groups) {
            if (g.startsWith("@")) {
                TeamList team = getTeamList(g.substring(1));
                return team.members.stream().anyMatch(m -> m.login.equals(login));
            }
            return g.equals(login);
        }
        return false;
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
            comment = DataCommonComment.findBotComment(this, itemId, commentId);
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
                botComment = new BotComment(itemId).setBodyString(commentBody);
            } else {
                // new comment
                comment = switch (itemType) {
                    case discussion ->
                        DataDiscussionComment.addComment(this, itemId, commentBody);
                    case issue, pull_request ->
                        DataIssueComment.addIssueComment(this, itemId, commentBody);
                    default -> {
                        logAndSendEmail(getLogId(), "addBotComment: Unknown event type " + itemType, null);
                        yield null;
                    }
                };
                botComment = comment == null ? null : new BotComment(itemId, comment);
            }
        } else if (botComment.requiresUpdate(commentBody)) {
            if (isDryRun()) {
                Log.debugf("[%s] updateBotComment would edit comment %s with %s", getLogId(), botComment.getCommentId(),
                        commentBody);
                botComment.setBodyString(commentBody);
            } else {
                comment = switch (itemType) {
                    case discussion ->
                        DataDiscussionComment.editComment(this, botComment.getCommentId(), commentBody);
                    case issue, pull_request ->
                        DataIssueComment.editIssueComment(this, botComment.getCommentId(), commentBody);
                    default -> {
                        logAndSendEmail(getLogId(), "updateItemDescription: Unknown event type " + itemType, null);
                        yield null;
                    }
                };
                // if an error happened, comment will be null, we should clear the cache
                botComment = comment == null ? null : botComment.setBodyString(commentBody);
            }
        } else {
            Log.debugf("[%s] updateBotComment: comment %s unchanged", getLogId(), botComment.getCommentId());
        }
        RECENT_BOT_CONTENT.put(itemId, botComment);
        return botComment;
    }

    protected BotComment createBotComment(String nodeId, DataCommonComment comment) {
        BotComment botComment = new BotComment(nodeId, comment);
        RECENT_BOT_CONTENT.put(nodeId, botComment);
        return botComment;
    }

    public DataCommonItem getItem(EventType eventType, String nodeId) {
        if (hasErrors()) {
            Log.debugf("[%s] getItem skipping due to errors", getLogId());
            return null;
        }
        return switch (eventType) {
            case discussion ->
                DataDiscussion.queryDiscussion(this, nodeId);
            case issue, pull_request ->
                DataCommonItem.queryItem(this, nodeId);
            default -> {
                logAndSendEmail(getLogId(), "getItem: Unknown event type " + eventType, null);
                yield null;
            }
        };
    }

    public DataCommonItem createItem(EventType eventType, String title, String description, Collection<DataLabel> labels) {
        if (hasErrors()) {
            Log.debugf("[%s] getItem skipping due to errors", getLogId());
            return null;
        }
        return switch (eventType) {
            case issue ->
                DataCommonItem.createIssue(this, title, description, labels);
            default -> {
                logAndSendEmail(getLogId(), "getItem: Unknown event type " + eventType, null);
                yield null;
            }
        };
    }

    public DataCommonItem updateItemDescription(EventType eventType, String nodeId, String bodyString) {
        if (isDryRun()) {
            Log.debugf("[%s] updateItemDescription would set body to: %s", getLogId(), bodyString);
            return null;
        }
        if (hasErrors()) {
            Log.debugf("[%s] updateItemDescription skipping due to errors", getLogId());
            return null;
        }

        return switch (eventType) {
            case discussion, discussion_comment ->
                DataDiscussion.editDiscussion(this, nodeId, bodyString);
            case issue, issue_comment ->
                DataCommonItem.editIssueDescription(this, nodeId, bodyString);
            case pull_request, pull_request_review ->
                DataCommonItem.editPullRequestDescription(this, nodeId, bodyString);
            default -> {
                logAndSendEmail(getLogId(), "updateItemDescription: Unknown event type " + eventType, null);
                yield null;
            }
        };
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
            return labels;
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

    public boolean isTeamMember(GHUser user, String teamFullName) {
        Set<GHUser> members = teamMembers(teamFullName);
        return members != null && members.contains(user);
    }

    public TeamList getTeamList(String teamFullName) {
        Set<GHUser> members = teamMembers(teamFullName);
        TeamList teamList = new TeamList(teamFullName, members);
        Log.debugf("[%s] %s members: %s", getLogId(), teamList.name, teamList.members);
        return teamList;
    }

    public void updateTeamList(String teamFullName, Set<GHUser> members) {
        TEAM_MEMBERS.put(teamFullName, members);
    }

    public void updateTeamList(GHOrganization org, GHTeam ghTeam) {
        // Normalize team name to include org name
        String relativeName = ghTeam.getName().replace(org.getLogin() + "/", "");
        String teamFullName = org.getLogin() + "/" + relativeName;

        Set<GHUser> members = execGitHubSync((gh, dryRun) -> {
            return ghTeam.getMembers();
        });
        if (hasErrors() || members == null) {
            TEAM_MEMBERS.invalidate(teamFullName);
        } else {
            TEAM_MEMBERS.put(teamFullName, members);
        }
    }

    public Set<GHUser> teamMembers(String teamFullName) {
        String orgName = toOrganizationName(teamFullName);
        String relativeName = toRelativeName(orgName, teamFullName);

        GHOrganization org = getOrganization(orgName);
        Set<GHUser> members = TEAM_MEMBERS.get(teamFullName);
        if (members == null) {
            members = execGitHubSync((gh, dryRun) -> {
                GHTeam ghTeam = org.getTeamByName(relativeName);
                return ghTeam == null ? Set.of() : ghTeam.getMembers();
            });
            if (hasErrors() || members == null) {
                return null;
            }
            TEAM_MEMBERS.put(teamFullName, members);
        }
        return members;
    }

    public String[] getErrorAddresses() {
        return ctx.botErrorEmailAddress();
    }

    public void logAndSendEmail(String logId, String title, Throwable t) {
        ctx.logAndSendEmail(logId, title, t, getErrorAddresses());
    }

    public void logAndSendEmail(String logId, String title, String body, Throwable t) {
        ctx.logAndSendEmail(logId, title, body, t, getErrorAddresses());
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
