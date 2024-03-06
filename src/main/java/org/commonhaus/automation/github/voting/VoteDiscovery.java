package org.commonhaus.automation.github.voting;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.commonhaus.automation.github.RepositoryAppConfig;
import org.commonhaus.automation.github.Voting;
import org.commonhaus.automation.github.Voting.Config;
import org.commonhaus.automation.github.model.DataDiscussion;
import org.commonhaus.automation.github.model.QueryHelper;
import org.commonhaus.automation.github.model.ScheduledQueryContext;
import org.kohsuke.github.GHApp;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHAuthenticatedAppInstallation;
import org.kohsuke.github.GHIOException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.ConfigFile.Source;
import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkiverse.githubapp.GitHubConfigFileProvider;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.vertx.mutiny.core.eventbus.EventBus;

@ApplicationScoped
public class VoteDiscovery {

    @Inject
    EventBus eventBus;

    @Inject
    QueryHelper queryHelper;

    @Inject
    GitHubClientProvider gitHubService;

    @Inject
    GitHubConfigFileProvider configProvider;

    @Scheduled(cron = "${automation.cron.expr:5 */3 * * *}")
    void discoverVotes() {
        GitHub ac = gitHubService.getApplicationClient();
        try {
            GHApp ghApp = ac.getApp();

            for (GHAppInstallation ghAppInstallation : ghApp.listInstallations()) {
                long ghiId = ghAppInstallation.getId();
                Log.debugf("App installation: %s", ghiId);

                GitHub github = gitHubService.getInstallationClient(ghiId);
                GHAuthenticatedAppInstallation ghai = github.getInstallation();

                for (GHRepository ghRepository : ghai.listRepositories()) {
                    Optional<RepositoryAppConfig.File> repoConfig = configProvider
                            .fetchConfigFile(ghRepository, RepositoryAppConfig.NAME, Source.DEFAULT,
                                    RepositoryAppConfig.File.class);
                    if (repoConfig.isPresent()) {
                        Voting.Config voteConfig = Voting.getVotingConfig(repoConfig.get());
                        if (voteConfig.isEnabled()) {
                            Log.debugf("[%s] Voting enabled", ghiId);
                            queryRepository(ghRepository, ghAppInstallation, voteConfig);
                        }
                    }
                }
            }
        } catch (GHIOException e) {
            Log.errorf(e, "[discoverVotes] Error making GH Request: %s", e.toString());
            if (Log.isDebugEnabled() && e.getResponseHeaderFields() != null) {
                e.getResponseHeaderFields()
                        .forEach((k, v) -> Log.debugf("%s: %s", k, v));
                e.printStackTrace();
            }
        } catch (Throwable t) {
            Log.errorf(t, "[discoverVotes] Error making GH Request: %s", t.toString());
            if (Log.isDebugEnabled()) {
                t.printStackTrace();
            }
        }
    }

    void queryRepository(GHRepository ghRepository, GHAppInstallation ghAppInstallation, Config voteConfig) {
        ScheduledQueryContext ctx = queryHelper.newScheduledQueryContext(ghRepository, ghAppInstallation);
        Log.debugf("Querying votes in repository: %s", ctx.getLogId());

        List<DataDiscussion> discussions = ctx.findDiscussionsWithLabel("vote/open");
        discussions.forEach(d -> eventBus.requestAndForget("voting", new VoteEvent(ctx, voteConfig, d)));
    }
}
