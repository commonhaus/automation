package org.commonhaus.automation.github.voting;

import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
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
import org.commonhaus.automation.github.model.QueryCache;
import org.commonhaus.automation.github.model.QueryHelper;
import org.commonhaus.automation.github.model.ScheduledQueryContext;
import org.kohsuke.github.GHIOException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.mutiny.core.eventbus.EventBus;

@ApplicationScoped
@UnlessBuildProfile("test")
public class VoteDiscovery {
    static final String ADDRESS = "vote-discovery";

    static class DiscoveryQueueMsg {
        ScheduledQueryContext ctx;
        Voting.Config voteConfig;

        public DiscoveryQueueMsg(ScheduledQueryContext ctx, Config voteConfig) {
            this.ctx = ctx;
            this.voteConfig = voteConfig;
        }
    }

    private final ConcurrentHashMap<String, Long> votingRepositories = new ConcurrentHashMap<>();

    @Inject
    AppConfig appConfig;

    @Inject
    EventBus eventBus;

    @Inject
    QueryHelper queryHelper;

    @Inject
    GitHubClientProvider gitHubService;

    /**
     * Update the configuration for a repository (received with a recent repository event)
     *
     * @param repoEvent
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

            // if this is the first event, query the repository
            if (repoEvent.discovery) {
                ScheduledQueryContext ctx = queryHelper.newScheduledQueryContext(ghRepository, repoEvent.installationId)
                        .addExisting(repoEvent.github);

                Log.debugf("[%s] queue vote discovery", ctx.getLogId());
                eventBus.send(VoteDiscovery.ADDRESS, new DiscoveryQueueMsg(ctx, voteConfig));
            }
        }
    }

    @Scheduled(cron = "${automation.cron-expr:13 27 */5 * * ?}")
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

                Voting.Config voteConfig = Voting.getVotingConfig(queryHelper.getConfiguration(ghRepository));
                if (voteConfig.isDisabled()) {
                    // Voting no longer enabled. Remove it
                    i.remove();
                } else {
                    ScheduledQueryContext ctx = queryHelper.newScheduledQueryContext(ghRepository, installationId)
                            .addExisting(github);
                    eventBus.send(VoteDiscovery.ADDRESS, new DiscoveryQueueMsg(ctx, voteConfig));
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

    @ConsumeEvent(value = VoteDiscovery.ADDRESS, blocking = true)
    void queryRepository(DiscoveryQueueMsg msg) {
        CheckStatus checkStatus = QueryCache.RECENT_VOTE_CHECK.computeIfAbsent(
                msg.ctx.getRepositoryId(), (k) -> new CheckStatus());
        if (!checkStatus.startScheduledUpdate()) {
            return;
        }
        try {
            Log.debugf("[%s] queryRepository", msg.ctx.getLogId());
            queryDiscussions(msg);
            queryIssues(msg);
        } finally {
            checkStatus.finishUpdate();
        }
    }

    void queryDiscussions(DiscoveryQueueMsg msg) {
        ScheduledQueryContext ctx = msg.ctx;
        Voting.Config voteConfig = msg.voteConfig;
        List<DataDiscussion> discussions = ctx.findDiscussionsWithLabel("vote/open");
        if (discussions == null) {
            return;
        }
        for (var discussion : discussions) {
            slowDown();
            Log.infof("[%s] discoverVotes: queue discussion #%s", ctx.getLogId(), discussion.number);
            ScheduledQueryContext discussionCtx = queryHelper.newScheduledQueryContext(ctx, EventType.discussion);
            eventBus.send(VoteEvent.ADDRESS, new VoteEvent(discussionCtx, voteConfig, discussion));
        }
    }

    // Issues or pull requests
    void queryIssues(DiscoveryQueueMsg msg) {
        ScheduledQueryContext ctx = msg.ctx;
        Voting.Config voteConfig = msg.voteConfig;
        List<DataCommonItem> issues = ctx.findIssuesWithLabel("vote/open");
        if (issues == null) {
            return;
        }
        for (var issue : issues) {
            slowDown();
            Log.infof("[%s] discoverVotes: queue issue #%s", ctx.getLogId(), issue.number);
            ScheduledQueryContext issueCtx = queryHelper.newScheduledQueryContext(ctx,
                    issue.isPullRequest ? EventType.pull_request : EventType.issue);
            eventBus.send(VoteEvent.ADDRESS, new VoteEvent(issueCtx, voteConfig, issue));
        }
    }

    private void slowDown() {
        try {
            TimeUnit.SECONDS.sleep(15);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
