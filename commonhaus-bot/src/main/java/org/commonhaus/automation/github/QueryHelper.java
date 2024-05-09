package org.commonhaus.automation.github;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import org.commonhaus.automation.AppConfig;
import org.commonhaus.automation.RepositoryConfigFile;
import org.commonhaus.automation.github.context.EventData;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.github.context.QueryCache;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.ConfigFile.Source;
import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkiverse.githubapp.GitHubConfigFileProvider;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

@ApplicationScoped
public class QueryHelper {
    private static final QueryCache REPO_CONFIG = QueryCache.create(
            "REPO_CONFIG", b -> b.expireAfterWrite(6, TimeUnit.HOURS));

    private final AppConfig botConfig;
    private final GitHubClientProvider gitHubClientProvider;
    private final GitHubConfigFileProvider configProvider;

    public QueryHelper(AppConfig botConfig, GitHubClientProvider gitHubClientProvider,
            GitHubConfigFileProvider configProvider) {
        this.botConfig = botConfig;
        this.gitHubClientProvider = gitHubClientProvider;
        this.configProvider = configProvider;
    }

    public EventQueryContext newQueryContext(EventData event) {
        return new EventQueryContext(botConfig, gitHubClientProvider, event);
    }

    public EventQueryContext newQueryContext(EventData eventData, GitHub github, DynamicGraphQLClient graphQLClient) {
        EventQueryContext ctx = newQueryContext(eventData);
        ctx.addExisting(github).addExisting(graphQLClient);
        return ctx;
    }

    public ScheduledQueryContext newScheduledQueryContext(GHRepository repository, long installationId) {
        return new ScheduledQueryContext(this, botConfig, gitHubClientProvider, repository, installationId);
    }

    public ScheduledQueryContext newScheduledQueryContext(ScheduledQueryContext ctx, EventType eventType) {
        return new ScheduledQueryContext(ctx, eventType);
    }

    void repositoryDiscovered(@Observes RepositoryDiscoveryEvent repoEvent) {
        // Update repo config cache (will be refreshed on every event. We have it, so update it.)
        Optional<RepositoryConfigFile> repoConfig = repoEvent.getRepositoryConfig();
        REPO_CONFIG.putCachedValue(repoEvent.getRepository().getNodeId(), repoConfig);
    }

    public void updateConfiguration(GHRepository repository, RepositoryConfigFile repositoryConfig) {
        REPO_CONFIG.putCachedValue(repository.getNodeId(), Optional.of(repositoryConfig));
    }

    public RepositoryConfigFile getConfiguration(GHRepository ghRepository) {
        Optional<RepositoryConfigFile> repoConfig = REPO_CONFIG.getCachedValue(ghRepository.getNodeId());
        if (repoConfig == null) {
            // unless we haven't had an event for a repo in a long while, this should more or less
            // never happen
            repoConfig = configProvider.fetchConfigFile(ghRepository,
                    botConfig.getConfigFileName(), Source.DEFAULT, botConfig.getConfigType());
            REPO_CONFIG.putCachedValue(ghRepository.getNodeId(), repoConfig);
        }
        return repoConfig.orElse(null);
    }
}
