package org.commonhaus.automation.github.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.commonhaus.automation.config.BotConfig;
import org.commonhaus.automation.github.queue.PeriodicUpdateQueue;
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
    Event<RepositoryDiscoveryEvent> repositoryDiscoveryEvent;

    @Inject
    Event<InstallationDiscoveryEvent> postInstallationDiscovery;

    @Inject
    Event<BootstrapDiscoveryEvent> postBootstrapDiscovery;

    @Inject
    Event<ConnectionEvent> connectionEvent;

    // discoverRepositories is/was being called twice. Avoid double discovery
    static final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * On startup, discover installations and repositories
     * (if discovery is enabled).
     *
     * @param ev
     */
    void discoverRepositories(@Observes StartupEvent ev) {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        Log.info("Repository discovery is " + (botConfig.isDiscoveryEnabled() ? "enabled" : "disabled"));
        if (!botConfig.isDiscoveryEnabled()) {
            return;
        }

        // Do the work from the queue
        periodicUpdateQueue.queue(this::discoverRepositories);
    }

    /**
     * Fire event to refresh connections for installations when a
     * GitHub event for an installation is received.
     *
     * @param event
     * @param github
     * @param graphQLClient
     */
    void onEvent(@RawEvent GitHubEvent event, GitHub github, DynamicGraphQLClient graphQLClient) {
        if (event == null || event.getInstallationId() == null) {
            return;
        }

        connectionEvent.fire(new ConnectionEvent(event.getInstallationId(), github, graphQLClient));
    }

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

                Log.debugf("[%s] Fire initial discovery events", ghiId);
                for (GHRepository repo : ghai.listRepositories()) {
                    repositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(
                            DiscoveryAction.ADDED, github, graphQLClient, ghiId,
                            repo, true));
                }
                Log.debugf("[%s] PostInitialDiscoveryEvent", ghiId);
                postInstallationDiscovery.fire(new InstallationDiscoveryEvent(ghiId, github, graphQLClient));
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
            postBootstrapDiscovery.fire(new BootstrapDiscoveryEvent(installations));
        }
    }
}
