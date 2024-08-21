package org.commonhaus.automation.github.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.json.JsonObject;

import org.commonhaus.automation.github.context.ContextService;
import org.commonhaus.automation.github.context.JsonAttribute;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHAuthenticatedAppInstallation;
import org.kohsuke.github.GHIOException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.ConfigFile.Source;
import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkiverse.githubapp.GitHubConfigFileProvider;
import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkiverse.githubapp.event.RawEvent;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

@Singleton
public class RepositoryDiscovery {

    @Inject
    Instance<ContextService> ctxInstance;

    @Inject
    GitHubClientProvider gitHubService;

    @Inject
    GitHubConfigFileProvider configProvider;

    @Inject
    Event<RepositoryDiscoveryEvent> repositoryDiscoveryEvent;

    @Inject
    Event<InstallationDiscoveryEvent> postInstallationDiscovery;

    @Inject
    Event<BootstrapDiscoveryEvent> postBootstrapDiscovery;

    private ContextService ctx;

    // discoverRepositories is/was being called twice. Avoid double discovery
    static final AtomicBoolean started = new AtomicBoolean(false);

    void discoverRepositories(@Observes StartupEvent ev) {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        ctx = ctxInstance.get();
        Log.info("Repository discovery is " + (ctx.isDiscoveryEnabled() ? "enabled" : "disabled"));
        if (!ctx.isDiscoveryEnabled()) {
            return;
        }

        List<Long> installations = new ArrayList<>();
        try {
            GitHub ac = gitHubService.getApplicationClient();
            for (GHAppInstallation ghAppInstallation : ac.getApp().listInstallations()) {
                long ghiId = ghAppInstallation.getId();
                GitHub github = gitHubService.getInstallationClient(ghiId);
                DynamicGraphQLClient graphQLClient = gitHubService.getInstallationGraphQLClient(ghiId);
                GHAuthenticatedAppInstallation ghai = github.getInstallation();

                Log.debugf("[%s] Fire initial discovery events", ghiId);
                for (GHRepository repo : ghai.listRepositories()) {
                    repositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(
                            DiscoveryAction.ADDED, github, graphQLClient, ghiId,
                            repo, fetchConfigFile(repo), true));
                }
                Log.debugf("[%s] PostInitialDiscoveryEvent", ghiId);
                postInstallationDiscovery.fire(new InstallationDiscoveryEvent(ghiId, github, graphQLClient));
                installations.add(ghiId);
            }
        } catch (GHIOException e) {
            ctx.logAndSendEmail("discoverRepositories", "Error making GH Request", e, null);
            if (Log.isDebugEnabled() && e.getResponseHeaderFields() != null) {
                e.getResponseHeaderFields()
                        .forEach((k, v) -> Log.debugf("%s: %s", k, v));
                e.printStackTrace();
            }
        } catch (Throwable t) {
            ctx.logAndSendEmail("discoverRepositories", "Error making GH Request", t, null);
            if (Log.isDebugEnabled()) {
                t.printStackTrace();
            }
        } finally {
            postBootstrapDiscovery.fire(new BootstrapDiscoveryEvent(installations));
        }
    }

    void onEvent(@RawEvent GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient) {
        ctx = ctxInstance.get();
        ctx.updateConnections(event.getInstallationId(), github, graphQLClient);
    }

    /**
     * Respond to installation changes
     */
    void onInstallationChange(@RawEvent(event = "installation") GitHubEvent gitHubEvent,
            GitHub github, DynamicGraphQLClient graphQLClient) {
        ctx = ctxInstance.get();

        String action = gitHubEvent.getAction();
        JsonObject payload = JsonAttribute.unpack(gitHubEvent.getPayload());
        JsonObject installation = JsonAttribute.installation.jsonObjectFrom(payload);
        long installationId = JsonAttribute.id.longFrom(installation);

        List<GHRepository> repositories = JsonAttribute.repositories.repositoriesFrom(payload);

        switch (action) {
            case "created", "unsuspend" -> {
                for (GHRepository repo : repositories) {
                    repositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(
                            DiscoveryAction.INSTALL_ADDED, github, graphQLClient, installationId,
                            repo, fetchConfigFile(repo), false));
                }
            }
            case "deleted", "suspend" -> {
                for (GHRepository repo : repositories) {
                    repositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(
                            DiscoveryAction.INSTALL_REMOVED, github, graphQLClient, installationId,
                            repo, null, false));
                }
            }
            default -> {
            }
        }
    }

    /**
     * Respond to App Installation repository changes.
     * Sender may be null if the event is from a webhook, which is not handled
     * by the GitHub API.
     */
    void onInstallationRepositoryChange(@RawEvent(event = "installation_repositories") GitHubEvent gitHubEvent,
            GitHub github, DynamicGraphQLClient graphQLClient) {
        ctx = ctxInstance.get();

        JsonObject payload = JsonAttribute.unpack(gitHubEvent.getPayload());
        JsonObject installation = JsonAttribute.installation.jsonObjectFrom(payload);
        long installationId = JsonAttribute.id.longFrom(installation);

        List<GHRepository> added = JsonAttribute.repositoriesAdded.repositoriesFrom(payload);
        List<GHRepository> removed = JsonAttribute.repositoriesRemoved.repositoriesFrom(payload);

        handleRepositoryChanges(github, graphQLClient, installationId,
                added == null ? List.of() : added,
                removed == null ? List.of() : removed);
    }

    protected void handleRepositoryChanges(GitHub github, DynamicGraphQLClient graphQLClient, long installationId,
            List<GHRepository> added, List<GHRepository> removed) {
        for (GHRepository repo : added) {
            repositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(
                    DiscoveryAction.ADDED, github, graphQLClient, installationId,
                    repo, fetchConfigFile(repo), false));
        }

        for (GHRepository repo : removed) {
            repositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(
                    DiscoveryAction.REMOVED, github, graphQLClient, installationId,
                    repo, null, false));
        }
    }

    Optional<?> fetchConfigFile(GHRepository ghRepository) {
        try {
            return configProvider.fetchConfigFile(ghRepository,
                    ctx.getConfigFileName(),
                    Source.DEFAULT,
                    ctx.getConfigType());
        } catch (Throwable t) {
            if (Log.isDebugEnabled()) {
                t.printStackTrace();
            }
            return Optional.empty();
        }

    }
}
