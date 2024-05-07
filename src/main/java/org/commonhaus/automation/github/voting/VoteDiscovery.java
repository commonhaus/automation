package org.commonhaus.automation.github.voting;

import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.AppConfig;
import org.commonhaus.automation.github.RepositoryDiscovery.RepositoryDiscoveryEvent;
import org.commonhaus.automation.github.Voting;
import org.commonhaus.automation.github.Voting.Config;
import org.commonhaus.automation.github.model.DataCommonItem;
import org.commonhaus.automation.github.model.DataDiscussion;
import org.commonhaus.automation.github.model.EventType;
import org.commonhaus.automation.github.model.QueryHelper;
import org.commonhaus.automation.github.model.ScheduledQueryContext;
import org.kohsuke.github.GHIOException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.vertx.mutiny.core.eventbus.EventBus;

@ApplicationScoped
@UnlessBuildProfile("test")
public class VoteDiscovery {
    private final ConcurrentHashMap<String, Long> votingRepositories = new ConcurrentHashMap<>();

    private final BlockingQueue<Runnable> taskQueue = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    @Inject
    AppConfig appConfig;

    @Inject
    EventBus eventBus;

    @Inject
    QueryHelper queryHelper;

    @Inject
    GitHubClientProvider gitHubService;

    public void startup(@Observes StartupEvent startup) {
        // Don't flood. Be leisurely for scheduled/cron queries
        executor.scheduleAtFixedRate(() -> {
            Runnable task = taskQueue.poll();
            if (task != null) {
                task.run();
            }
        }, 0, 10, TimeUnit.SECONDS);
    }

    public void shutdown(@Observes ShutdownEvent shutdown) {
        executor.shutdown();
    }

    /**
     * Update the configuration for a repository (received with a recent repository
     * event).
     */
    public void repositoryDiscovered(@Observes RepositoryDiscoveryEvent repoEvent) {
        long ghiId = repoEvent.installationId;
        GHRepository ghRepository = repoEvent.ghRepository;

        Voting.Config voteConfig = Voting.getVotingConfig(repoEvent.repoConfig.orElse(null));
        if (voteConfig.isDisabled()) {
            votingRepositories.remove(repoEvent.ghRepository.getFullName());
        } else {
            // update map.
            votingRepositories.put(repoEvent.ghRepository.getFullName(), ghiId);

            // if this is the first event, schedule a query for votes
            if (repoEvent.discovery) {
                scheduleQueryRepository(voteConfig, ghRepository, ghiId, repoEvent.github);
            }
        }
    }

    @Scheduled(cron = "${automation.cron-expr:13 27 */5 * * ?}")
    void discoverVotes() {
        try {
            Iterator<Entry<String, Long>> i = votingRepositories.entrySet().iterator();
            while (i.hasNext()) {
                var e = i.next();
                String repoFullName = e.getKey();
                Long installationId = e.getValue();

                GitHub github = gitHubService.getInstallationClient(installationId);
                GHRepository ghRepository = github.getRepository(repoFullName);

                Voting.Config voteConfig = Voting.getVotingConfig(queryHelper.getConfiguration(ghRepository));
                if (voteConfig.isDisabled()) {
                    // Voting no longer enabled. Remove it
                    i.remove();
                } else {
                    scheduleQueryRepository(voteConfig, ghRepository, installationId, github);
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

    void scheduleQueryRepository(Voting.Config voteConfig, GHRepository ghRepository, long installationId,
            GitHub github) {
        Log.infof("discoverVotes: queue repository %s", ghRepository.getFullName());
        taskQueue.add(() -> {
            ScheduledQueryContext ctx = queryHelper.newScheduledQueryContext(ghRepository, installationId)
                    .addExisting(github);
            queryRepository(ctx, voteConfig);
        });
    }

    void queryRepository(ScheduledQueryContext ctx, Config voteConfig) {
        queryDiscussions(ctx, voteConfig);
        queryIssues(ctx, voteConfig);
    }

    void queryDiscussions(ScheduledQueryContext ctx, Config voteConfig) {
        List<DataDiscussion> discussions = ctx.findDiscussionsWithLabel("vote/open");
        if (discussions == null) {
            return;
        }
        for (var discussion : discussions) {
            scheduleQueryIssue(ctx, EventType.discussion, voteConfig, discussion);
        }
    }

    // Issues or pull requests
    void queryIssues(ScheduledQueryContext ctx, Config voteConfig) {
        List<DataCommonItem> issues = ctx.findIssuesWithLabel("vote/open");
        if (issues == null) {
            return;
        }
        for (var issue : issues) {
            scheduleQueryIssue(ctx, issue.isPullRequest ? EventType.pull_request : EventType.issue, voteConfig, issue);
        }
    }

    /**
     * Send an event to {@link VotingConsumer} to check for updates on an open vote.
     *
     * @param ctx source scheduled context
     * @param type type of item (issue, pull_request, or discussion)
     * @param voteConfig voting configuration for the repository
     * @param item the item (as a common object)
     */
    void scheduleQueryIssue(ScheduledQueryContext ctx, EventType type, Voting.Config voteConfig, DataCommonItem item) {
        Log.infof("[%s] discoverVotes: queue %s #%s", ctx.getLogId(), type, item.number);
        taskQueue.add(() -> {
            ScheduledQueryContext itemCtx = queryHelper.newScheduledQueryContext(ctx, type);
            eventBus.send(VoteEvent.ADDRESS, new VoteEvent(itemCtx, voteConfig, item));
        });
    }
}
