package org.commonhaus.automation.github;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import org.commonhaus.automation.AppConfig;
import org.commonhaus.automation.github.RepositoryAppConfig.File;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHAuthenticatedAppInstallation;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHIOException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.ConfigFile.Source;
import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkiverse.githubapp.GitHubConfigFileProvider;
import io.quarkiverse.githubapp.event.Discussion;
import io.quarkiverse.githubapp.event.DiscussionComment;
import io.quarkiverse.githubapp.event.Issue;
import io.quarkiverse.githubapp.event.IssueComment;
import io.quarkiverse.githubapp.event.Label;
import io.quarkiverse.githubapp.event.PullRequest;
import io.quarkiverse.githubapp.event.PullRequestReview;
import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;

@ApplicationScoped
@UnlessBuildProfile("test")
public class RepositoryDiscovery {

    public static class RepositoryDiscoveryEvent {
        public final boolean discovery;
        public final long installationId;
        public final GHRepository ghRepository;
        public final Optional<File> repoConfig;
        public final GitHub github;

        private RepositoryDiscoveryEvent(GitHub github, long installationId, GHRepository ghRepository,
                Optional<File> repoConfig, boolean bootstrap) {
            this.discovery = bootstrap;
            this.installationId = installationId;
            this.ghRepository = ghRepository;
            this.repoConfig = repoConfig;
            this.github = github;
        }

        public RepositoryDiscoveryEvent(GitHub github, long installationId, GHRepository ghRepository,
                Optional<File> repoConfig) {
            this.discovery = false;
            this.installationId = installationId;
            this.ghRepository = ghRepository;
            this.repoConfig = repoConfig;
            this.github = github;
        }
    }

    @Inject
    AppConfig appConfig;

    @Inject
    GitHubClientProvider gitHubService;

    @Inject
    GitHubConfigFileProvider configProvider;

    @Inject
    Event<RepositoryDiscoveryEvent> repositoryDiscoveryEvent;

    @Startup
    void init() {
        if (appConfig.isDiscoveryEnabled()) {
            Log.info("Repository discovery is enabled");
            discoverRepositories();
        } else {
            Log.info("Repository discovery is disabled");
        }
    }

    void discoverRepositories() {
        try {
            GitHub ac = gitHubService.getApplicationClient();
            for (GHAppInstallation ghAppInstallation : ac.getApp().listInstallations()) {
                long ghiId = ghAppInstallation.getId();
                GitHub github = gitHubService.getInstallationClient(ghiId);
                GHAuthenticatedAppInstallation ghai = github.getInstallation();

                for (GHRepository ghRepository : ghai.listRepositories()) {
                    Optional<RepositoryAppConfig.File> repoConfig = configProvider
                            .fetchConfigFile(ghRepository, RepositoryAppConfig.NAME, Source.DEFAULT,
                                    RepositoryAppConfig.File.class);
                    repositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(github,
                            ghiId, ghRepository, repoConfig, true));
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

    // Fire repositoryDiscoveryEvents for all events that can affect the repository.
    // This allows components to update the information used for scheduled
    // tasks, or webhooks that aren't emitted directly for the GH App (e.g. sponsors)

    void onDiscussionEvent(GitHub github, @Discussion GHEventPayload.Discussion payload,
            @ConfigFile(RepositoryAppConfig.NAME) RepositoryAppConfig.File repoConfigFile) {

        repositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(github,
                payload.getInstallation().getId(), payload.getRepository(), Optional.ofNullable(repoConfigFile)));
    }

    void onDiscussionCommentEvent(GitHub github, @DiscussionComment GHEventPayload.DiscussionComment payload,
            @ConfigFile(RepositoryAppConfig.NAME) RepositoryAppConfig.File repoConfigFile) {

        repositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(github,
                payload.getInstallation().getId(), payload.getRepository(), Optional.ofNullable(repoConfigFile)));
    }

    void onIssueEvent(GitHub github, @Issue GHEventPayload.Issue payload,
            @ConfigFile(RepositoryAppConfig.NAME) RepositoryAppConfig.File repoConfigFile) {

        repositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(github,
                payload.getInstallation().getId(), payload.getRepository(), Optional.ofNullable(repoConfigFile)));
    }

    void onIssueCommentEvent(GitHub github, @IssueComment GHEventPayload.IssueComment payload,
            @ConfigFile(RepositoryAppConfig.NAME) RepositoryAppConfig.File repoConfigFile) {

        repositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(github,
                payload.getInstallation().getId(), payload.getRepository(), Optional.ofNullable(repoConfigFile)));
    }

    void onRepositoryLabelChange(GitHub github, @Label GHEventPayload.Label payload,
            @ConfigFile(RepositoryAppConfig.NAME) RepositoryAppConfig.File repoConfigFile) {
        repositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(github,
                payload.getInstallation().getId(), payload.getRepository(), Optional.ofNullable(repoConfigFile)));
    }

    void onPullRequestEvent(GitHub github, @PullRequest GHEventPayload.PullRequest payload,
            @ConfigFile(RepositoryAppConfig.NAME) RepositoryAppConfig.File repoConfigFile) {

        repositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(github,
                payload.getInstallation().getId(), payload.getRepository(), Optional.ofNullable(repoConfigFile)));
    }

    void onPullRequestReviewEvent(GitHub github, @PullRequestReview GHEventPayload.PullRequestReview payload,
            @ConfigFile(RepositoryAppConfig.NAME) RepositoryAppConfig.File repoConfigFile) {

        repositoryDiscoveryEvent.fire(new RepositoryDiscoveryEvent(github,
                payload.getInstallation().getId(), payload.getRepository(), Optional.ofNullable(repoConfigFile)));
    }
}
