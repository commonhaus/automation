package org.commonhaus.automation.github.voting;

import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.commonhaus.automation.github.RepositoryAppConfig;
import org.commonhaus.automation.github.Voting;
import org.commonhaus.automation.github.Voting.Config;
import org.commonhaus.automation.github.model.DataCommonItem;
import org.commonhaus.automation.github.model.DataDiscussion;
import org.commonhaus.automation.github.model.EventType;
import org.commonhaus.automation.github.model.QueryCache;
import org.commonhaus.automation.github.model.QueryHelper;
import org.commonhaus.automation.github.model.ScheduledQueryContext;
import org.commonhaus.automation.github.voting.VotingConsumer.CheckStatus;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHAuthenticatedAppInstallation;
import org.kohsuke.github.GHIOException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.ConfigFile.Source;
import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkiverse.githubapp.GitHubConfigFileProvider;
import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import io.vertx.mutiny.core.eventbus.EventBus;

@ApplicationScoped
@UnlessBuildProfile("test")
public class VoteDiscovery {

    private final ConcurrentHashMap<String, Long> votingRepositories = new ConcurrentHashMap<>();

    @Inject
    EventBus eventBus;

    @Inject
    QueryHelper queryHelper;

    @Inject
    GitHubClientProvider gitHubService;

    @Inject
    GitHubConfigFileProvider configProvider;

    @Inject
    ExecutorService executorService;

    @Startup
    void init() {
        executorService.submit(() -> {
            discoverRepositories();
        });
    }

    void discoverRepositories() {
        Log.debug("VoteDiscovery initialized: initial scan for repositories");
        try {
            GitHub ac = gitHubService.getApplicationClient();
            for (GHAppInstallation ghAppInstallation : ac.getApp().listInstallations()) {
                long ghiId = ghAppInstallation.getId();
                GitHub github = gitHubService.getInstallationClient(ghiId);
                GHAuthenticatedAppInstallation ghai = github.getInstallation();

                for (GHRepository ghRepository : ghai.listRepositories()) {
                    Voting.Config voteConfig = getConfiguration(ghRepository);
                    if (voteConfig.isDisabled()) {
                        continue;
                    }
                    votingRepositories.put(ghRepository.getFullName(), ghiId);
                    queryRepository(queryHelper.newScheduledQueryContext(ghRepository, ghiId),
                            voteConfig);
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

    @Scheduled(cron = "${automation.cron.expr:13 27 */5 * * ?}")
    void discoverVotes() {
        CheckStatus checkStatus = QueryCache.RECENT_VOTE_CHECK.computeIfAbsent(
                "discoverVotes", (k) -> new CheckStatus());
        if (!checkStatus.startScheduledUpdate()) {
            return;
        }

        try {
            Iterator<Entry<String, Long>> i = votingRepositories.entrySet().iterator();
            while (i.hasNext()) {
                var e = i.next();
                String repoFullName = e.getKey();
                Long installationId = e.getValue();

                GitHub github = gitHubService.getInstallationClient(installationId);

                GHRepository ghRepository = github.getRepository(repoFullName);
                Voting.Config voteConfig = getConfiguration(ghRepository);
                if (voteConfig.isDisabled()) {
                    // Voting no longer enabled. Remove it
                    i.remove();
                } else {
                    queryRepository(queryHelper.newScheduledQueryContext(ghRepository, installationId),
                            voteConfig);
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
        } finally {
            checkStatus.finishUpdate();
        }
    }

    void queryRepository(ScheduledQueryContext ctx, Voting.Config voteConfig) {
        CheckStatus checkStatus = QueryCache.RECENT_VOTE_CHECK.computeIfAbsent(
                ctx.getRepositoryId(), (k) -> new CheckStatus());
        if (!checkStatus.startScheduledUpdate()) {
            return;
        }
        try {
            Log.debugf("[%s] discoverVotes", ctx.getLogId());
            queryDiscussions(ctx, voteConfig);
            queryIssues(ctx, voteConfig);
        } finally {
            checkStatus.finishUpdate();
        }
    }

    void queryDiscussions(ScheduledQueryContext ctx, Voting.Config voteConfig) {
        List<DataDiscussion> discussions = ctx.findDiscussionsWithLabel("vote/open");
        if (discussions == null) {
            return;
        }
        for (var discussion : discussions) {
            slowDown();
            Log.infof("[%s] discoverVotes: queue discussion#%s", ctx.getLogId(), discussion.number);
            ScheduledQueryContext discussionCtx = queryHelper.newScheduledQueryContext(ctx, EventType.discussion);
            eventBus.send("voting", new VoteEvent(discussionCtx, voteConfig, discussion));
        }
    }

    void queryIssues(ScheduledQueryContext ctx, Voting.Config voteConfig) {
        List<DataCommonItem> issues = ctx.findIssuesWithLabel("vote/open");
        if (issues == null) {
            return;
        }
        for (var issue : issues) {
            slowDown();
            Log.infof("[%s] discoverVotes: queue issue #%s", ctx.getLogId(), issue.number);
            ScheduledQueryContext issueCtx = queryHelper.newScheduledQueryContext(ctx, EventType.issue);
            eventBus.send("voting", new VoteEvent(issueCtx, voteConfig, issue));
        }
    }

    private void slowDown() {
        try {
            TimeUnit.SECONDS.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Voting.Config getConfiguration(GHRepository ghRepository) {
        Optional<RepositoryAppConfig.File> repoConfig = configProvider
                .fetchConfigFile(ghRepository, RepositoryAppConfig.NAME, Source.DEFAULT,
                        RepositoryAppConfig.File.class);
        if (repoConfig.isEmpty()) {
            return Config.DISABLED;
        }
        return Voting.getVotingConfig(repoConfig.get());
    }
}
