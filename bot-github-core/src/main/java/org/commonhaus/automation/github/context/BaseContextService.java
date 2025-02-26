package org.commonhaus.automation.github.context;

import org.commonhaus.automation.config.BotConfig;
import org.commonhaus.automation.mail.LogMailer;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.GitHubClientProvider;
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

    private final GitHubClientProvider gitHubClientProvider;
    private final EventBus bus;
    private final BotConfig baseBotConfig;
    private final LogMailer logMailer;

    public BaseContextService(BotConfig baseBotConfig, GitHubClientProvider gitHubClientProvider,
            EventBus bus, LogMailer logMailer) {
        this.gitHubClientProvider = gitHubClientProvider;
        this.baseBotConfig = baseBotConfig;
        this.bus = bus;
        this.logMailer = logMailer;
    }

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
        // if the GH credentials are invalid, invalidate the GraphQL client, too
        BaseQueryCache.CONNECTION.invalidate("graphQL-" + installationId);

        gh = gitHubClientProvider.getInstallationClient(installationId);
        BaseQueryCache.CONNECTION.put("gh-" + installationId, gh);
        return gh;
    }

    public DynamicGraphQLClient getInstallationGraphQLClient(long installationId) {
        DynamicGraphQLClient graphQLClient = BaseQueryCache.CONNECTION.get("graphQL-" + installationId);
        if (graphQLClient != null) {
            return graphQLClient;
        }

        graphQLClient = gitHubClientProvider.getInstallationGraphQLClient(installationId);
        BaseQueryCache.CONNECTION.put("graphQL-" + installationId, graphQLClient);
        return graphQLClient;
    }

    @Override
    public String[] botErrorEmailAddress() {
        return logMailer.botErrorEmailAddress();
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
    public void sendEmail(String logId, String title, String body, String htmlBody, String[] addresses) {
        logMailer.sendEmail(logId, title, body, htmlBody, addresses);
    }
}
