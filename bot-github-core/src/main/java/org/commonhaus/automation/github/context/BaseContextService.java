package org.commonhaus.automation.github.context;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.commonhaus.automation.config.BotConfig;
import org.commonhaus.automation.config.EmailNotification;
import org.commonhaus.automation.github.discovery.ConnectionEvent;
import org.commonhaus.automation.github.scopes.ScopedInstallationMap;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.mail.LogMailer;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkiverse.githubapp.GitHubEvent;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;
import io.vertx.mutiny.core.eventbus.EventBus;

/**
 * Base class for context services.
 * <p>
 * This class provides the basic functionality for context services, including:
 * <ul>
 * <li>Access to the bot configuration</li>
 * <li>Access to the GitHub client and GraphQL client for a given installation ID</li>
 * <li>Access to the reading configuration files from repositories</li>
 * <li>Common methods for logging and sending emails</li>
 *
 * </ul>
 */
public abstract class BaseContextService implements ContextService {

    @Inject
    protected ScopedInstallationMap installationMap;

    @Inject
    protected GitHubClientProvider gitHubClientProvider;

    @Inject
    protected EventBus bus;

    @Inject
    protected BotConfig baseBotConfig;

    @Inject
    protected LogMailer logMailer;

    public EventBus getBus() {
        return bus;
    }

    public boolean isDiscoveryEnabled() {
        return baseBotConfig.isDiscoveryEnabled();
    }

    public boolean isDryRun() {
        return baseBotConfig.isDryRun();
    }

    public GitHub getInstallationClient(long installationId) {
        GitHub gh = BaseQueryCache.CONNECTION.get("gh-" + installationId);
        if (gh != null) {
            return gh;
        }
        // there is no way to test the graphql client's credentials for validity.
        // if the GH credentials are invalid, invalidate the GraphQL client
        // so it reconnects next time.
        BaseQueryCache.CONNECTION.invalidate("graphQL-" + installationId);

        gh = gitHubClientProvider.getInstallationClient(installationId);
        updateConnection(installationId, gh);
        return gh;
    }

    public void resetConnection(long installationId) {
        if (installationId < 0) { // user session
            return;
        }
        BaseQueryCache.CONNECTION.invalidate("gh-" + installationId);
        BaseQueryCache.CONNECTION.invalidate("graphQL-" + installationId);
    }

    public void updateConnection(long installationId, GitHub gh) {
        BaseQueryCache.CONNECTION.put("gh-" + installationId, gh);
    }

    public DynamicGraphQLClient getInstallationGraphQLClient(long installationId) {
        DynamicGraphQLClient graphQLClient = BaseQueryCache.CONNECTION.get("graphQL-" + installationId);
        if (graphQLClient != null) {
            return graphQLClient;
        }

        graphQLClient = gitHubClientProvider.getInstallationGraphQLClient(installationId);
        updateConnection(installationId, graphQLClient);
        return graphQLClient;
    }

    public void updateConnection(long installationId, DynamicGraphQLClient graphQLClient) {
        BaseQueryCache.CONNECTION.put("graphQL-" + installationId, graphQLClient);
    }

    public ScopedQueryContext getOrgScopedQueryContext(String teamOrgName) {
        return installationMap.getOrgScopedQueryContext(this, teamOrgName);
    }

    @Override
    public String[] botErrorEmailAddress() {
        return logMailer.botErrorEmailAddress();
    }

    public String[] getErrorAddresses(EmailNotification notifications) {
        Set<String> addresses = new HashSet<>();
        if (notifications != null) {
            Collections.addAll(addresses, notifications.errors());
        }
        Collections.addAll(addresses, botErrorEmailAddress());
        return addresses.toArray(new String[0]);
    }

    @Override
    public void logAndSendEmail(String logId, String title, Throwable t, String[] addresses) {
        logMailer.logAndSendEmail(logId, title, t, addresses);
    }

    @Override
    public void logAndSendEmail(String logId, String title, String body, Throwable t, String[] addresses) {
        logMailer.logAndSendEmail(logId, title, body, t, addresses);
    }

    @Override
    public void sendEmail(String logId, String title, String body, String[] addresses) {
        logMailer.sendEmail(logId, title, body, addresses);
    }

    /**
     * Listen for connection events, and update the cached github/graphql clients
     */
    static class EventListener {

        @Inject
        Instance<ContextService> ctxInstance;

        protected void updateConnection(@ObservesAsync ConnectionEvent connectEvent) {
            GitHubEvent event = connectEvent.event();
            if (ctxInstance.isResolvable()) {
                ctxInstance.get().updateConnection(event.getInstallationId(), connectEvent.github());
                ctxInstance.get().updateConnection(event.getInstallationId(), connectEvent.graphQLClient());
            }
        }
    }
}
