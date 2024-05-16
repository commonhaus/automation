package org.commonhaus.automation.github;

import java.util.Optional;

import jakarta.inject.Singleton;

import org.commonhaus.automation.RepositoryConfigFile;
import org.commonhaus.automation.config.BotConfig;
import org.commonhaus.automation.github.context.BaseContextService;
import org.commonhaus.automation.github.context.EventData;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.github.context.QueryContext;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkiverse.githubapp.GitHubConfigFileProvider;
import io.smallrye.config.ConfigMapping;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;
import io.vertx.mutiny.core.eventbus.EventBus;

@Singleton
public class AppContextService extends BaseContextService {

    @ConfigMapping(prefix = "automation.voting")
    interface AppConfig {
        Optional<String> cron();
    }

    public static abstract class AppQueryContext extends QueryContext {
        protected AppQueryContext(AppContextService contextService, long installationId) {
            super(contextService, installationId);
        }

        public AppQueryContext(AppQueryContext parent) {
            super(parent.ctx, parent.installationId);
        }
    }

    AppConfig appConfig;

    public AppContextService(BotConfig data, AppConfig appConfig, GitHubClientProvider gitHubClientProvider,
            GitHubConfigFileProvider configProvider, EventBus bus) {
        super(data, gitHubClientProvider, configProvider, bus);
        this.appConfig = appConfig;
    }

    public EventQueryContext newQueryContext(EventData event) {
        return new EventQueryContext(this, event);
    }

    public EventQueryContext newQueryContext(EventData eventData, GitHub github, DynamicGraphQLClient graphQLClient) {
        EventQueryContext qc = newQueryContext(eventData);
        qc.addExisting(github).addExisting(graphQLClient);
        return qc;
    }

    public ScheduledQueryContext newScheduledQueryContext(GHRepository repository, long installationId) {
        return new ScheduledQueryContext(this, repository, installationId);
    }

    public ScheduledQueryContext newScheduledQueryContext(ScheduledQueryContext qc, EventType eventType) {
        return new ScheduledQueryContext(qc, eventType);
    }

    public Class<RepositoryConfigFile> getConfigType() {
        return RepositoryConfigFile.class;
    }

    public String getConfigFileName() {
        return RepositoryConfigFile.NAME;
    }
}
