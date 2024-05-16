package org.commonhaus.automation.github.discovery;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import org.commonhaus.automation.github.context.ContextService;
import org.commonhaus.automation.github.context.JsonAttribute;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHAuthenticatedAppInstallation;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHIOException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.ConfigFile.Source;
import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkiverse.githubapp.GitHubConfigFileProvider;
import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkiverse.githubapp.event.Installation;
import io.quarkiverse.githubapp.event.InstallationRepositories;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
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

    void discoverRepositories(@Observes StartupEvent ev) {
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
            ctx.logAndSendEmail("discoverRepositories", "Error making GH Request", e);
            if (Log.isDebugEnabled() && e.getResponseHeaderFields() != null) {
                e.getResponseHeaderFields()
                        .forEach((k, v) -> Log.debugf("%s: %s", k, v));
                e.printStackTrace();
            }
        } catch (Throwable t) {
            ctx.logAndSendEmail("discoverRepositories", "Error making GH Request", t);
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
    void onInstallationChange(GitHubEvent event, GitHub github,
            @Installation GHEventPayload.Installation installation) {
        String action = installation.getAction();
        long ghiId = installation.getInstallation().getId();
        switch (action) {
            case "created", "unsuspend" -> {
                for (GHRepository repo : installation.getRepositories()) {
                    repositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(
                            DiscoveryAction.ADDED, github, ghiId,
                            repo, fetchConfigFile(repo)));
                }
            }
            case "deleted", "suspend" -> {
                // Reduced payload available. We'll use our own parse to construct slim
                // disconnected repositories from what we have
                JsonObject payload = JsonAttribute.unpack(event.getPayload());
                JsonArray repositories = JsonAttribute.repositories.jsonArrayFrom(payload);
                for (JsonValue v : repositories) {
                    GHRepository repo = JsonAttribute.repository.repositoryFrom(v.asJsonObject());
                    repositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(
                            DiscoveryAction.REMOVED, github, ghiId,
                            repo, null));
                }
            }
            default -> {
            }
        }
    }

    /**
     * Respond to App Installation repository changes
     *
     * @param event
     * @param github
     * @param repositories
     */
    void onInstallationRepositoryChange(GitHubEvent event, GitHub github,
            @InstallationRepositories GHEventPayload.InstallationRepositories repositories) {

        for (GHRepository repo : repositories.getRepositoriesAdded()) {
            repositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(
                    DiscoveryAction.ADDED, github, repositories.getInstallation().getId(),
                    repo, fetchConfigFile(repo)));
        }

        for (GHRepository repo : repositories.getRepositoriesRemoved()) {
            repositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(
                    DiscoveryAction.REMOVED, github, repositories.getInstallation().getId(),
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
