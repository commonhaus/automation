package org.commonhaus.automation;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.commonhaus.automation.config.EmailNotification;
import org.commonhaus.automation.github.context.GitHubQueryContext.GitHubParameterApiCall;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.HttpException;

import com.fasterxml.jackson.core.type.TypeReference;

import io.quarkus.logging.Log;
import io.smallrye.graphql.client.GraphQLError;
import io.smallrye.graphql.client.Response;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;
import io.smallrye.graphql.client.impl.ResponseImpl;

public abstract class GraphQLQueryContext {
    protected final List<GraphQLError> errors = new ArrayList<>(1);
    protected final List<Throwable> exceptions = new ArrayList<>(1);
    protected volatile int countAuthRetry = 0;

    protected final ContextService ctx;

    public GraphQLQueryContext(ContextService contextService) {
        this.ctx = contextService;
    }

    public abstract String getLogId();

    public abstract DynamicGraphQLClient getGraphQLClient();

    protected abstract void cleanupAuthenticationError();

    /**
     * @return true if the context is in dry run mode
     */
    public boolean isDryRun() {
        return ctx.isDryRun();
    }

    /**
     * Add an exception to the context. This could be a GraphQL error
     * or a GitHub API exception.
     */
    public void addException(Throwable t) {
        exceptions.add(t);
    }

    /**
     * @return true if there are errors or exceptions
     */
    public boolean hasErrors() {
        return !errors.isEmpty() || !exceptions.isEmpty();
    }

    /**
     * Clear errors and exceptions
     */
    public void clearErrors() {
        errors.clear();
        exceptions.clear();
    }

    /**
     * @return PackagedException if there are multiple exceptions or errors; or null
     */
    public PackagedException bundleExceptions() {
        return hasErrors()
                ? new PackagedException(exceptions, errors)
                : null;
    }

    /**
     * Check the GraphQLError for a specific type
     *
     * @param e GraphQL error
     * @param testType GraphQL error type to test for
     * @return true if the error has the specified type
     */
    private boolean hasFieldError(GraphQLError e, String testType) {
        var others = e.getOtherFields();
        var type = others == null ? null : others.get("type");
        return testType.equals(type);
    }

    /**
     * @return true if a not found exception occurred
     */
    public boolean hasNotFound() {
        return exceptions.stream().anyMatch(e -> e instanceof GHFileNotFoundException)
                || errors.stream().anyMatch(e -> hasFieldError(e, "NOT_FOUND"));
    }

    /**
     * If there are any not found exceptions, clear them and return true
     *
     * @return true if there were any not found exceptions
     */
    public boolean checkRemoveNotFound() {
        int size = exceptions.size() + errors.size();
        exceptions.removeIf(e -> e instanceof GHFileNotFoundException);
        errors.removeIf(e -> hasFieldError(e, "NOT_FOUND"));
        // did we remove anything?
        return (exceptions.size() + errors.size()) != size;
    }

    /**
     * @return true if a conflict occurred
     */
    public boolean hasConflict() {
        return getConflict() != null;
    }

    /**
     * @return the Exception if a conflict occurred (409 response code or message contains 409)
     */
    public HttpException getConflict() {
        return (HttpException) exceptions.stream()
                .filter(e -> e instanceof HttpException && ((HttpException) e).getResponseCode() == 409)
                .findFirst().orElse(null);
    }

    /**
     * If there is a conflict exception, clear it and return true
     *
     * @return true if there was a conflict exception
     */
    public boolean checkRemoveConflict() {
        int size = exceptions.size();
        exceptions.removeIf(e -> e instanceof HttpException && ((HttpException) e).getResponseCode() == 409);
        return exceptions.size() != size;
    }

    /**
     * Check if the error is a network error that can be retried
     *
     * @return true if there is a network error that can be retried
     */
    public boolean hasRetriableNetworkError() {
        // Unauthorized can be retried after connection is refreshed.
        return exceptions.stream().anyMatch(e -> List.of(HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_PROXY_AUTH,
                HttpURLConnection.HTTP_BAD_GATEWAY,
                HttpURLConnection.HTTP_CLIENT_TIMEOUT,
                HttpURLConnection.HTTP_GATEWAY_TIMEOUT)
                .contains(getResponseCode(e)));
    }

    private int getResponseCode(Throwable e) {
        if (e instanceof HttpException) {
            return ((HttpException) e).getResponseCode();
        }
        return -1;
    }

    // Helper method to extract status code from response
    protected Integer extractStatusCode(Response response) {
        if (response instanceof ResponseImpl) {
            Map<String, List<String>> meta = response.getTransportMeta();
            if (meta != null && meta.containsKey(ResponseImpl.STATUS_CODE)) {
                List<String> codes = meta.get(ResponseImpl.STATUS_CODE);
                if (codes != null && !codes.isEmpty()) {
                    try {
                        return Integer.parseInt(codes.get(0));
                    } catch (NumberFormatException ignored) {
                        // Ignore parsing errors
                    }
                }
            }
        }
        return null;
    }

    /**
     * Run a synchronous GraphQL query with parameters.
     * Overall behavior is similar to {@link #execGitHubSync(GitHubParameterApiCall)}
     * <p>
     * If a mutation is detected and the bot is in dryRun mode, the query will be logged
     * but not executed. It will return null, as it can not otherwise determine the correct
     * response type.
     * <p>
     * Errors executing GraphQL queries will be stored as errors in the context
     * to be inspected by the caller. An email will also be sent to the configured error
     * addresses, as this can indicate a connection issue or other problem with the
     * GraphQL endpoint.
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

            // Check if the response has authentication errors
            if (response != null) {
                // Check HTTP status code first
                Integer statusCode = extractStatusCode(response);
                if (statusCode != null && (statusCode == 401 || statusCode == 403)) {
                    if (countAuthRetry++ < 2) {
                        Log.debugf(
                                "[%s] execQuerySync: Auth error detected (status code %d), refreshing token and retrying",
                                getLogId(), statusCode);
                        cleanupAuthenticationError();
                        return execQuerySync(query, variables);
                    } else {
                        Log.debugf("[%s] execQuerySync: Auth error retry limit reached", getLogId());
                    }
                }
                if (response.hasError()) {
                    Log.debugf("[%s] execQuerySync: GraphQL Error: %s", getLogId(), response.getErrors());
                    errors.addAll(response.getErrors());
                }
            }
        } catch (Throwable e) {
            Log.debugf("[%s] execQuerySync: Throwable: %s", getLogId(), e);
            addException(e);
        }
        return response;
    }

    public <T> T readYamlContent(String content, Class<T> type) {
        try {
            return ctx.parseYamlContent(content, type);
        } catch (IOException e) {
            addException(e);
            return null;
        }
    }

    public <T> T readYamlContent(String content, TypeReference<T> type) {
        try {
            return ctx.parseYamlContent(content, type);
        } catch (IOException e) {
            addException(e);
            return null;
        }
    }

    public String[] getErrorAddresses() {
        return ctx.botErrorEmailAddress();
    }

    public String[] getErrorAddresses(EmailNotification notifications) {
        return ctx.getErrorAddresses(notifications);
    }

    /**
     * Log an error and send an collected errors and exceptions to the
     * configured error addresses.
     * Uses this (QueryContext) logId and error addresses.
     *
     * @param title
     * @see #getLogId()
     * @see #getErrorAddresses()
     * @see ContextService#logAndSendEmail(String, String, Throwable, String[])
     */
    public void logAndSendContextErrors(String title) {
        ctx.logAndSendEmail(getLogId(), title, bundleExceptions(), getErrorAddresses());
    }

    public void logAndSendContextErrors(String title, EmailNotification notifications) {
        ctx.logAndSendEmail(getLogId(), title, bundleExceptions(), getErrorAddresses(notifications));
    }

    /**
     * Log an error and send an email to the configured error addresses.
     * Uses this (QueryContext) logId and error addresses.
     *
     * @param title
     * @param t
     * @see #getLogId()
     * @see #getErrorAddresses()
     * @see ContextService#logAndSendEmail(String, String, Throwable, String[])
     */
    public void logAndSendEmail(String title, Throwable t) {
        ctx.logAndSendEmail(getLogId(), title, t, getErrorAddresses());
    }

    public void logAndSendEmail(String title, Throwable t, EmailNotification notifications) {
        ctx.logAndSendEmail(getLogId(), title, t, getErrorAddresses(notifications));
    }

    /**
     * Log an error and send an email with the specified body to the configured error addresses.
     * Uses this (QueryContext) logId and error addresses.
     *
     * @param title
     * @param body
     * @param t
     * @see #getLogId()
     * @see #getErrorAddresses()
     * @see ContextService#logAndSendEmail(String, String, String, Throwable, String[])
     */
    public void logAndSendEmail(String title, String body, Throwable t) {
        ctx.logAndSendEmail(getLogId(), title, body, t, getErrorAddresses());
    }

    public void logAndSendEmail(String title, String body, Throwable t, EmailNotification notifications) {
        ctx.logAndSendEmail(getLogId(), title, body, t, getErrorAddresses(notifications));
    }

    /**
     * Send an email with the specified body to the configured error addresses.
     * Simple wrapper around ContextService to avoid injected dependencies.
     *
     * @param logId
     * @param title
     * @param body
     * @param addresses
     * @see ContextService#sendEmail(String, String, String, String[])
     */
    public void sendEmail(String logId, String title, String body, String[] addresses) {
        ctx.sendEmail(logId, title, body, addresses);
    }
}
