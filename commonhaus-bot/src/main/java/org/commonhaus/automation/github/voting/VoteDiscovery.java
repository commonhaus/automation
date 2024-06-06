package org.commonhaus.automation.github.voting;

import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.RepositoryConfigFile;
import org.commonhaus.automation.github.AppContextService;
import org.commonhaus.automation.github.ScheduledQueryContext;
import org.commonhaus.automation.github.ScheduledQueryContext.ScheduledItemQueryContext;
import org.commonhaus.automation.github.context.DataCommonItem;
import org.commonhaus.automation.github.context.DataDiscussion;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
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
    EventBus eventBus;

    @Inject
    AppContextService ctx;

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
     * Event handler for repository discovery.
     */
    public void repositoryDiscovered(@Observes RepositoryDiscoveryEvent repoEvent) {
        long ghiId = repoEvent.installationId();
        GHRepository repo = repoEvent.repository();
        Optional<RepositoryConfigFile> repoConfig = repoEvent.getRepositoryConfig();
        VoteConfig voteConfig = VoteConfig.getVotingConfig(repoConfig.orElse(null));

        if (repoEvent.removed() || voteConfig.isDisabled()) {
            votingRepositories.remove(repo.getFullName());
        } else {
            // update map.
            votingRepositories.put(repo.getFullName(), ghiId);
            scheduleQueryRepository(voteConfig, repo, ghiId, repoEvent.github());
        }
    }

    @Scheduled(cron = "${automation.voting.cron:13 27 */5 * * ?}")
    void discoverVotes() {
        try {
            Iterator<Entry<String, Long>> i = votingRepositories.entrySet().iterator();
            while (i.hasNext()) {
                var e = i.next();
                String repoFullName = e.getKey();
                Long installationId = e.getValue();

                GitHub github = gitHubService.getInstallationClient(installationId);
                GHRepository repo = github.getRepository(repoFullName);

                RepositoryConfigFile file = ctx.getConfiguration(repo);
                VoteConfig voteConfig = VoteConfig.getVotingConfig(file);
                if (voteConfig.isDisabled()) {
                    // Voting no longer enabled. Remove it
                    i.remove();
                } else {
                    scheduleQueryRepository(voteConfig, repo, installationId, github);
                }
            }
        } catch (GHIOException e) {
            ctx.logAndSendEmail("discoverVotes", "Error making GH Request", e, null);
            if (Log.isDebugEnabled() && e.getResponseHeaderFields() != null) {
                e.getResponseHeaderFields()
                        .forEach((k, v) -> Log.debugf("%s: %s", k, v));
                e.printStackTrace();
            }
        } catch (Throwable t) {
            ctx.logAndSendEmail("discoverVotes", "Error making GH Request", t, null);
            if (Log.isDebugEnabled()) {
                t.printStackTrace();
            }
        }
    }

    void scheduleQueryRepository(VoteConfig voteConfig, GHRepository ghRepository, long installationId,
            GitHub github) {
        Log.infof("discoverVotes: queue repository %s", ghRepository.getFullName());
        taskQueue.add(() -> {
            ScheduledQueryContext qc = ctx.newScheduledQueryContext(ghRepository, installationId)
                    .addExisting(github);
            queryRepository(qc, voteConfig);
        });
    }

    void queryRepository(ScheduledQueryContext qc, VoteConfig voteConfig) {
        queryDiscussions(qc, voteConfig);
        queryIssues(qc, voteConfig);
    }

    void queryDiscussions(ScheduledQueryContext qc, VoteConfig voteConfig) {
        List<DataDiscussion> discussions = qc.findDiscussionsWithLabel("vote/open");
        if (discussions == null) {
            return;
        }
        for (var discussion : discussions) {
            scheduleQueryIssue(qc, EventType.discussion, voteConfig, discussion);
        }
    }

    // Issues or pull requests
    void queryIssues(ScheduledQueryContext qc, VoteConfig voteConfig) {
        List<DataCommonItem> issues = qc.findIssuesWithLabel("vote/open");
        if (issues == null) {
            return;
        }
        for (var issue : issues) {
            scheduleQueryIssue(qc, issue.isPullRequest ? EventType.pull_request : EventType.issue, voteConfig, issue);
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
    void scheduleQueryIssue(ScheduledQueryContext qc, EventType type, VoteConfig voteConfig, DataCommonItem item) {
        Log.infof("[%s] discoverVotes: queue %s #%s", qc.getLogId(), type, item.number);
        taskQueue.add(() -> {
            ScheduledItemQueryContext itemCtx = ctx.newScheduledItemQueryContext(qc, type);
            eventBus.send(VoteEvent.ADDRESS, new VoteEvent(itemCtx, voteConfig, item, type));
        });
    }
}
