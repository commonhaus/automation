package org.commonhaus.automation.github.discovery;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHAuthenticatedAppInstallation;
import org.kohsuke.github.GHIOException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.ConfigFile.Source;
import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkiverse.githubapp.GitHubConfigFileProvider;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class RepositoryDiscovery {

    @Inject
    GitHubClientProvider gitHubService;

    @Inject
    GitHubConfigFileProvider configProvider;

    @Inject
    Instance<DiscoveryConfig> appConfigInstance;

    @Inject
    Event<RepositoryDiscoveryEvent> repositoryDiscoveryEvent;

    private DiscoveryConfig appConfig;

    void discoverRepositories(@Observes StartupEvent ev) {
        appConfig = appConfigInstance.get();
        if (appConfig == null) {
            Log.error("No DiscoveryConfig found");
            return;
        }

        Log.info("Repository discovery is " + (appConfig.isDiscoveryEnabled() ? "enabled" : "disabled"));
        if (!appConfig.isDiscoveryEnabled()) {
            return;
        }

        try {
            GitHub ac = gitHubService.getApplicationClient();
            for (GHAppInstallation ghAppInstallation : ac.getApp().listInstallations()) {
                long ghiId = ghAppInstallation.getId();
                GitHub github = gitHubService.getInstallationClient(ghiId);
                GHAuthenticatedAppInstallation ghai = github.getInstallation();

                for (GHRepository ghRepository : ghai.listRepositories()) {
                    repositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(github,
                            ghiId, ghRepository, fetchConfigFile(ghRepository)));
                }
            }
        } catch (GHIOException e) {
            Log.errorf(e, "[discoverRepositories] Error making GH Request: %s", e.toString());
            if (Log.isDebugEnabled() && e.getResponseHeaderFields() != null) {
                e.getResponseHeaderFields()
                        .forEach((k, v) -> Log.debugf("%s: %s", k, v));
                e.printStackTrace();
            }
        } catch (Throwable t) {
            Log.errorf(t, "[discoverRepositories] Error making GH Request: %s", t.toString());
            if (Log.isDebugEnabled()) {
                t.printStackTrace();
            }
        }
    }

    Optional<?> fetchConfigFile(GHRepository ghRepository) {
        return configProvider.fetchConfigFile(ghRepository,
                appConfig.getConfigFileName(),
                Source.DEFAULT,
                appConfig.getConfigType());
    }
}
