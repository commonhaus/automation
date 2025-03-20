package org.commonhaus.automation.github.context;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import jakarta.inject.Inject;

import org.commonhaus.automation.config.BotConfig;
import org.commonhaus.automation.config.EmailNotification;
import org.commonhaus.automation.github.scopes.ScopedInstallationMap;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.mail.LogMailer;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkus.logging.Log;
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
        GitHub gh = BaseQueryCache.getCachedGitHubClient(installationId);
        if (gh == null) {
            Log.debugf("GitHub client not found or expired for %s, creating new connection", installationId);
            BaseQueryCache.resetCachedClients(installationId);
            gh = gitHubClientProvider.getInstallationClient(installationId);
            if (gh != null) {
                BaseQueryCache.putCachedGithubClient(installationId, gh);
            }
        }
        return gh;
    }

    public DynamicGraphQLClient getInstallationGraphQLClient(long installationId) {
        DynamicGraphQLClient graphQLClient = BaseQueryCache.getCachedGraphQLClient(installationId);
        if (graphQLClient == null) {
            graphQLClient = gitHubClientProvider.getInstallationGraphQLClient(installationId);
            if (graphQLClient != null) {
                BaseQueryCache.putCachedGraphQLClient(installationId, graphQLClient);
            }
        }
        return graphQLClient;
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
}
