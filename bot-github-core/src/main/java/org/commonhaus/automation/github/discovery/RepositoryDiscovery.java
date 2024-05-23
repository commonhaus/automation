package org.commonhaus.automation.github.discovery;

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

        try {
            GitHub ac = gitHubService.getApplicationClient();
            for (GHAppInstallation ghAppInstallation : ac.getApp().listInstallations()) {
                long ghiId = ghAppInstallation.getId();
                GitHub github = gitHubService.getInstallationClient(ghiId);
                GHAuthenticatedAppInstallation ghai = github.getInstallation();

                for (GHRepository repo : ghai.listRepositories()) {
                    repositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(
                            DiscoveryAction.ADDED, github, ghiId,
                            repo, fetchConfigFile(repo)));
                }
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
        }
    }

    /**
     * Respond to installation changes
     *
     * @param event
     * @param github
     * @param installation
     */
    void onInstallationChange(@RawEvent(event = "installation") GitHubEvent gitHubEvent, GitHub github) {
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
                            DiscoveryAction.ADDED, github, installationId,
                            repo, fetchConfigFile(repo)));
                }
            }
            case "deleted", "suspend" -> {
                for (GHRepository repo : repositories) {
                    repositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(
                            DiscoveryAction.REMOVED, github, installationId,
                            repo, null));
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
     *
     * @param event
     * @param github
     * @param repositories
     */
    void onInstallationRepositoryChange(@RawEvent(event = "installation_repositories") GitHubEvent gitHubEvent, GitHub github) {
        ctx = ctxInstance.get();

        JsonObject payload = JsonAttribute.unpack(gitHubEvent.getPayload());
        JsonObject installation = JsonAttribute.installation.jsonObjectFrom(payload);
        long installationId = JsonAttribute.id.longFrom(installation);

        List<GHRepository> added = JsonAttribute.repositoriesAdded.repositoriesFrom(payload);
        List<GHRepository> removed = JsonAttribute.repositoriesRemoved.repositoriesFrom(payload);

        handleRepositoryChanges(github, installationId,
                added == null ? List.of() : added,
                removed == null ? List.of() : removed);
    }

    protected void handleRepositoryChanges(GitHub github, long installationId,
            List<GHRepository> added, List<GHRepository> removed) {
        for (GHRepository repo : added) {
            repositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(
                    DiscoveryAction.ADDED, github, installationId,
                    repo, fetchConfigFile(repo)));
        }

        for (GHRepository repo : removed) {
            repositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(
                    DiscoveryAction.REMOVED, github, installationId,
                    repo, null));
        }
    }

    Optional<?> fetchConfigFile(GHRepository ghRepository) {
        return configProvider.fetchConfigFile(ghRepository,
                ctx.getConfigFileName(),
                Source.DEFAULT,
                ctx.getConfigType());
    }
}
