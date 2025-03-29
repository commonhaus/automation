package org.commonhaus.automation.github.discovery;

import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.json.JsonObject;

import org.commonhaus.automation.JsonAttributeAccessor;
import org.commonhaus.automation.config.BotConfig;
import org.commonhaus.automation.github.context.BaseQueryCache;
import org.commonhaus.automation.github.context.JsonAttribute;
import org.commonhaus.automation.queue.PeriodicUpdateQueue;
import org.commonhaus.automation.mail.LogMailer;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHAuthenticatedAppInstallation;
import org.kohsuke.github.GHIOException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkiverse.githubapp.event.RawEvent;
import io.quarkus.arc.Arc;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

@Singleton
public class RepositoryDiscovery {

    @Inject
    BotConfig botConfig;

    @Inject
    PeriodicUpdateQueue periodicUpdateQueue;

    @Inject
    GitHubClientProvider gitHubService;

    @Inject
    Event<RepositoryDiscoveryEvent> fireRepositoryDiscoveryEvent;

    @Inject
    Event<InstallationDiscoveryEvent> fireInstallationDiscoveryEvent;

    @Inject
    Event<BootstrapDiscoveryEvent> fireBootstrapDiscovery;

    /**
     * On startup, discover installations and repositories
     * (if discovery is enabled).
     *
     * @param ev
     */
    void discoverRepositories(@Observes StartupEvent ev) {
        Log.info("Repository discovery is " + (botConfig.isDiscoveryEnabled() ? "enabled" : "disabled"));
        if (!botConfig.isDiscoveryEnabled()) {
            return;
        }

        // Do the work from the queue
        periodicUpdateQueue.queue("discovery", this::discoverRepositories);
    }

    protected void handleRepositoryChanges(GitHub github, DynamicGraphQLClient graphQLClient, long installationId,
            List<GHRepository> added, List<GHRepository> removed) {
        for (GHRepository repo : added) {
            fireRepositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(
                    DiscoveryAction.ADDED, github, graphQLClient, installationId,
                    repo, false));
        }

        for (GHRepository repo : removed) {
            fireRepositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(
                    DiscoveryAction.REMOVED, github, graphQLClient, installationId,
                    repo, false));
        }
    }

    /**
     * Discover repositories for all installations.
     */
    void discoverRepositories() {
        LogMailer mailer = Arc.container().instance(LogMailer.class).orElse(new LogMailer());

        Log.info("Discovering repositories");
        List<Long> installations = new ArrayList<>();
        try {
            GitHub ac = gitHubService.getApplicationClient();
            // List installations for this GitHub App: roughly, each organization
            for (GHAppInstallation ghAppInstallation : ac.getApp().listInstallations()) {
                long ghiId = ghAppInstallation.getId();
                GitHub github = gitHubService.getInstallationClient(ghiId);
                GHAuthenticatedAppInstallation ghai = github.getInstallation();
                DynamicGraphQLClient graphQLClient = gitHubService.getInstallationGraphQLClient(ghiId);

                BaseQueryCache.putCachedGithubClient(ghiId, github);
                BaseQueryCache.putCachedGraphQLClient(ghiId, graphQLClient);

                Log.debugf("[%s] Fire initial discovery events", ghiId);
                for (GHRepository repo : ghai.listRepositories()) {
                    fireRepositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(
                            DiscoveryAction.ADDED, github, graphQLClient, ghiId,
                            repo, true));
                }
                Log.debugf("[%s] PostInitialDiscoveryEvent", ghiId);
                fireInstallationDiscoveryEvent.fire(
                        new InstallationDiscoveryEvent(DiscoveryAction.ADDED, ghiId, github, graphQLClient));
                installations.add(ghiId);
            }
        } catch (GHIOException e) {
            mailer.logAndSendEmail("discoverRepositories", "Error making GH Request", e, null);
            if (Log.isDebugEnabled() && e.getResponseHeaderFields() != null) {
                e.getResponseHeaderFields()
                        .forEach((k, v) -> Log.debugf("%s: %s", k, v));
                e.printStackTrace();
            }
        } catch (Throwable t) {
            mailer.logAndSendEmail("discoverRepositories", "Error making GH Request", t, null);
            if (Log.isDebugEnabled()) {
                t.printStackTrace();
            }
        } finally {
            fireBootstrapDiscovery.fire(new BootstrapDiscoveryEvent(installations));
        }
    }

    /**
     * Parter to Repository Discovery:
     * This is converted into a multiplexer to handle github events.
     */
    static class GitHubEventHandler {

        @Inject
        RepositoryDiscovery repositoryDiscovery;

        @Inject
        Event<ConnectionEvent> fireConnectionEvent;

        void onEvent(@RawEvent GitHubEvent event,
                GitHub github, DynamicGraphQLClient graphQLClient) {
            if (event == null || event.getInstallationId() == null) {
                return;
            }

            BaseQueryCache.putCachedGithubClient(event.getInstallationId(), github);
            BaseQueryCache.putCachedGraphQLClient(event.getInstallationId(), graphQLClient);

            fireConnectionEvent.fire(
                    new ConnectionEvent(event, github, graphQLClient));
        }

        /**
         * Respond to installation changes
         */
        void onInstallationChange(@RawEvent(event = "installation") GitHubEvent gitHubEvent,
                GitHub github, DynamicGraphQLClient graphQLClient) {

            String action = gitHubEvent.getAction();
            JsonObject payload = JsonAttributeAccessor.unpack(gitHubEvent.getPayload());
            JsonObject installation = JsonAttribute.installation.jsonObjectFrom(payload);
            long installationId = JsonAttribute.id.longFrom(installation);

            List<GHRepository> repositories = JsonAttribute.repositories.repositoriesFrom(payload);

            switch (action) {
                case "created", "unsuspend" -> {
                    for (GHRepository repo : repositories) {
                        repositoryDiscovery.fireRepositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(
                                DiscoveryAction.INSTALL_ADDED, github, graphQLClient, installationId,
                                repo, false));
                    }
                    repositoryDiscovery.fireInstallationDiscoveryEvent
                            .fire(new InstallationDiscoveryEvent(DiscoveryAction.INSTALL_ADDED,
                                    installationId, github, graphQLClient));
                }
                case "deleted", "suspend" -> {
                    for (GHRepository repo : repositories) {
                        repositoryDiscovery.fireRepositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(
                                DiscoveryAction.INSTALL_REMOVED, github, graphQLClient, installationId,
                                repo, false));
                    }
                    repositoryDiscovery.fireInstallationDiscoveryEvent
                            .fire(new InstallationDiscoveryEvent(DiscoveryAction.INSTALL_REMOVED,
                                    installationId, github, graphQLClient));
                }
                default -> {
                }
            }
        }

        /**
         * Respond to App Installation repository changes.
         *
         * Sender may be null if the event is from a webhook, which is not handled
         * by the GitHub API.
         */
        void onInstallationRepositoryChange(@RawEvent(event = "installation_repositories") GitHubEvent gitHubEvent,
                GitHub github, DynamicGraphQLClient graphQLClient) {

            JsonObject payload = JsonAttributeAccessor.unpack(gitHubEvent.getPayload());
            JsonObject installation = JsonAttribute.installation.jsonObjectFrom(payload);
            long installationId = JsonAttribute.id.longFrom(installation);

            List<GHRepository> added = JsonAttribute.repositoriesAdded.repositoriesFrom(payload);
            List<GHRepository> removed = JsonAttribute.repositoriesRemoved.repositoriesFrom(payload);

            repositoryDiscovery.handleRepositoryChanges(github, graphQLClient, installationId,
                    added == null ? List.of() : added,
                    removed == null ? List.of() : removed);
        }
    }
}
