package org.commonhaus.automation.github.hr.voting;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.config.RouteSupplier;
import org.commonhaus.automation.github.context.BotComment;
import org.commonhaus.automation.github.context.DataCommonComment;
import org.commonhaus.automation.github.context.DataCommonItem;
import org.commonhaus.automation.github.context.DataDiscussion;
import org.commonhaus.automation.github.context.DataLabel;
import org.commonhaus.automation.github.context.DataReaction;
import org.commonhaus.automation.github.context.EventData;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.github.context.GitHubQueryContext;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent.RdePriority;
import org.commonhaus.automation.github.hr.AppContextService;
import org.commonhaus.automation.github.hr.config.ConfigWatcher;
import org.commonhaus.automation.github.hr.config.VoteConfig;
import org.commonhaus.automation.github.hr.rules.MatchLabel;
import org.commonhaus.automation.github.queue.PeriodicUpdateQueue;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.mail.LogMailer;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.ReactionContent;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkus.logging.Log;
import io.quarkus.mailer.MailTemplate.MailTemplateInstance;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class VoteProcessor {

    public final static String MANUAL_VOTE_RESULT = "vote::result";

    public static final String VOTE_DONE = "vote/done";
    public static final String VOTE_OPEN = "vote/open";
    public static final String VOTE_PROCEED = "vote/proceed";
    public static final String VOTE_QUORUM = "vote/quorum";
    public static final String VOTE_REVISE = "vote/revise";

    static final MatchLabel HAS_OPEN_VOTE = new MatchLabel(List.of(VOTE_OPEN));

    @CheckedTemplate
    static class Templates {
        public static native MailTemplateInstance votingErrorEvent(DataCommonItem item,
                VoteEvent event, String title, String body, String htmlBody,
                String dateTime);
    }

    // standard prefix, used when no badge is configured
    public static final String prefixMatch = "\\*\\*Vote progress\\*\\* tracked in \\[this comment]";
    // when a badge is configured, this is the prefix
    public static final String badgeMatch = "\\[!\\[.*?]\\(.*?\\)]";
    public static final String linkMatch = "\\[.*?Vote progress]";
    public static final Pattern botCommentPattern = Pattern.compile(
            "(?:" + prefixMatch + "|" + badgeMatch + "|" + linkMatch + ")" +
                    "\\(([^ )]+) ?(?:\"([^\"]+)\")?\\)\\.?", // the juicy part of the URL
            Pattern.CASE_INSENSITIVE);

    private static volatile String lastRun = "never";

    private final ConcurrentHashMap<String, Long> votingRepositories = new ConcurrentHashMap<>();

    @Inject
    ObjectMapper objectMapper;

    @Inject
    AppContextService ctx;

    @Inject
    ConfigWatcher configWatcher;

    @Inject
    PeriodicUpdateQueue periodicUpdate;

    @Inject
    GitHubClientProvider gitHubService;

    @Inject
    LogMailer mailer;

    void startup(@Observes StartupEvent startup) {
        RouteSupplier.registerSupplier("Votes recounted", () -> lastRun);
    }

    public void repositoryDiscovered(@Observes @Priority(value = RdePriority.APP_EVENT) RepositoryDiscoveryEvent repoEvent) {
        Log.infof("⚙️ 🗳️ VoteProcessor.repositoryDiscovered: %s", repoEvent.repository().getFullName());
        lastRun = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

        long ghiId = repoEvent.installationId();
        GHRepository repo = repoEvent.repository();
        VoteConfig voteConfig = ctx.getVoteConfig(repo.getFullName());

        if (repoEvent.removed() || voteConfig.isDisabled()) {
            votingRepositories.remove(repo.getFullName());
        } else {
            // update map.
            votingRepositories.put(repo.getFullName(), ghiId);
            scheduleQueryRepository(ghiId, repo.getFullName());
        }
    }

    // Quartz cron expression: s m h dom mon dow year(optional)
    @Scheduled(cron = "${automation.hausRules.cron.voting:0 23 */3 * * ?}")
    void discoverVotes() {
        Log.info("⏰ 🗳️ Scheduled: count votes");

        var i = votingRepositories.entrySet().iterator();
        while (i.hasNext()) {
            var e = i.next();
            String repoFullName = e.getKey();
            Long installationId = e.getValue();
            VoteConfig voteConfig = ctx.getVoteConfig(repoFullName);
            if (voteConfig.isDisabled()) {
                // Voting no longer enabled. Remove it
                i.remove();
            } else {
                periodicUpdate.queue(repoFullName,
                        () -> scheduleQueryRepository(installationId, repoFullName));
            }
        }
        lastRun = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    public void reconcileVoteEvent(VoteEvent event) {
        periodicUpdate.queueReconciliation(event.getTaskGroup(),
                () -> processVoteCount(event));
    }

    /**
     * Reconciliation event for a vote item.
     * May be skipped if change event or reconciliation task for the same
     * item follows in the queue.
     *
     * Avoid relying on potentially stale data from the initial event
     *
     * @param nodeId
     */
    public void processVoteCount(VoteEvent event) {
        // Get current vote config
        VoteConfig votingConfig = ctx.getVoteConfig(event.getRepoFullName());
        if (votingConfig.isDisabled()) {
            return;
        }

        // Create a fresh query context for the current state
        ScopedQueryContext qc = event.createQueryContext(ctx);

        // Fetch current item state
        DataCommonItem item = qc.getItem(event.getItemType(), event.getItemNodeId());
        if (item == null || qc.hasErrors()) {
            handleErrors(qc, event.getTaskGroup(), "item not found or can't be retrieved",
                    () -> processVoteCount(event));
            return;
        }
        if (!HAS_OPEN_VOTE.matches(qc, item.id)) {
            Log.debugf("[%s] VoteProcessor.processVoteCount: item is not open", event.getLogId());
            return;
        }

        // Check that repository has required labels (update item if not)
        if (!repoHasLabels(qc, votingConfig, item, event)) {
            return; // Exit if required labels are missing
        }
        countVotes(qc, votingConfig, item, event);
    }

    private void countVotes(ScopedQueryContext qc, VoteConfig votingConfig, DataCommonItem item,
            VoteEvent event) {

        try {
            VoteInformation voteInfo = getVoteInformation(qc, votingConfig, item, event);
            if (voteInfo == null) {
                return;
            }

            // Find manual vote result comments if the item is closed
            String manualResultComment = VoteQueryCache.MANUAL_RESULT_COMMENT_ID.get(event.getItemNodeId());
            final List<DataCommonComment> resultComments = (item.closed || manualResultComment != null)
                    ? findManualResultComments(qc, votingConfig, event)
                    : List.of();

            Log.debugf("[🗳️ %s] countVotes: counting open vote (%s / %s)", event.getLogId(),
                    voteInfo.voteType, votingConfig.voteThreshold);

            final List<DataReaction> reactions = voteInfo.countComments()
                    ? List.of()
                    : findHumanReactions(qc, event);
            final List<DataCommonComment> comments = voteInfo.countComments()
                    ? findHumanComments(qc, event)
                    : List.of();
            // Tally the votes
            VoteTally tally = new VoteTally(voteInfo, reactions, comments, resultComments);

            // Add or update a bot comment summarizing the vote.
            String commentBody = tally.toMarkdown(item.closed);
            String jsonData = objectMapper.writeValueAsString(tally);
            commentBody += "\r\n<!-- vote::data " + jsonData + " -->";

            updateBotComment(qc, votingConfig, event, item, commentBody);

            if (tally.hasQuorum) {
                qc.addLabel(item.id, VOTE_QUORUM);
            }
            if (tally.isDone) {
                // remove the open label first so we don't process votes again
                qc.removeLabels(item.id, List.of(VOTE_OPEN));
                qc.addLabel(item.id, VOTE_DONE);
            }

            if (qc.hasErrors()) {
                sendVotingErrorEmail(qc, votingConfig, item, event, null);
            }
        } catch (Throwable e) {
            sendVotingErrorEmail(qc, votingConfig, item, event, e);
        }
    }

    private List<DataCommonComment> findHumanComments(ScopedQueryContext qc, VoteEvent voteEvent) {
        // Skip all bot comments
        List<DataCommonComment> comments = qc.getComments(voteEvent.getItemNodeId(),
                x -> !qc.isBot(x.author.login));
        return comments;
    }

    private List<DataCommonComment> findManualResultComments(ScopedQueryContext qc, VoteConfig config, VoteEvent voteEvent) {
        List<DataCommonComment> comments = qc.getComments(voteEvent.getItemNodeId(),
                x -> isManualVoteResult(qc, config, x));
        return comments;
    }

    private List<DataReaction> findHumanReactions(ScopedQueryContext qc, VoteEvent voteEvent) {
        // GraphQL fetch of all reactions on item (return if none)
        // Could query by group first, but pagination happens either way.
        List<DataReaction> reactions = new ArrayList<>(qc.getReactions(voteEvent.getItemNodeId()));

        // The bot's votes never count.
        reactions.removeIf(x -> {
            if (qc.isBot(x.user.login)) {
                if (x.reactionContent == ReactionContent.CONFUSED) {
                    // If we were previously confused, we aren't anymore.
                    qc.removeBotReaction(voteEvent.getItemNodeId(), ReactionContent.CONFUSED);
                }
                return true;
            }
            return false;
        });
        return reactions;
    }

    // Make sure other labels are present
    private boolean repoHasLabels(ScopedQueryContext qc, VoteConfig votingConfig, DataCommonItem item, VoteEvent voteEvent) {
        List<String> requiredLabels = List.of(VOTE_DONE, VOTE_PROCEED, VOTE_REVISE, VOTE_QUORUM);
        Collection<DataLabel> voteLabels = qc.findLabels(requiredLabels);

        if (voteLabels.size() != requiredLabels.size()) {
            qc.addBotReaction(voteEvent.getItemNodeId(), ReactionContent.CONFUSED);
            String comment = "The following labels must be defined in this repository:\r\n"
                    + String.join(", ", requiredLabels) + "\r\n\r\n"
                    + "Please ensure all labels have been defined.";
            updateBotComment(qc, votingConfig, voteEvent, item, comment);
            return false;
        }
        return true;
    }

    private void updateBotComment(ScopedQueryContext qc, VoteConfig votingConfig, VoteEvent voteEvent, DataCommonItem item,
            String commentBody) {
        BotComment comment = qc.updateBotComment(botCommentPattern,
                voteEvent.getItemType(), voteEvent.getItemNodeId(),
                commentBody, item.body);
        if (comment != null) {
            // Use informaton from the existing comment to construct replacement text
            String newBody = updateItemText(qc, votingConfig, item, comment);
            if (newBody.equals(item.body)) {
                Log.debugf("[%s] voting.checkVotes: item description unchanged", qc.getLogId());
            } else {
                qc.updateItemDescription(voteEvent.getItemType(), voteEvent.getItemNodeId(), newBody);
            }
        }
    }

    public String updateItemText(ScopedQueryContext qc, VoteConfig votingConfig, DataCommonItem item, BotComment comment) {
        String prefix = getVoteHeaderText(qc, votingConfig, item, comment);
        Matcher matcher = botCommentPattern.matcher(item.body);
        if (matcher.find()) {
            return matcher.replaceFirst(prefix);
        }
        return prefix + "\r\n\r\n" + item.body;
    }

    public String getVoteHeaderText(ScopedQueryContext qc, VoteConfig votingConfig, DataCommonItem item, BotComment comment) {
        if (votingConfig.status != null && votingConfig.status.badge() != null) {
            String badgeLink = votingConfig.status.badge()
                    .replace("{{repoName}}", qc.getRepository().getFullName())
                    .replace("{{number}}", item.number + "");

            return comment.markdownLink("![🗳️ Vote progress](" + badgeLink + ")");
        } else {
            return comment.markdownLink("🗳️ Vote progress");
        }
    }

    // Get information about vote mechanics (return if bad data)
    private VoteInformation getVoteInformation(ScopedQueryContext qc, VoteConfig voteConfig, DataCommonItem item,
            VoteEvent voteEvent) {
        final VoteInformation voteInfo = new VoteInformation(ctx, qc, voteConfig, item, voteEvent);
        if (!voteInfo.isValid()) {
            qc.addBotReaction(voteEvent.getItemNodeId(), ReactionContent.CONFUSED);
            Log.debugf("[🤔 %s] voting.checkVotes: invalid vote information -- %s", qc.getLogId(),
                    voteInfo.getErrorContent());

            // Add or update a bot comment summarizing what went wrong.
            updateBotComment(qc, voteConfig, voteEvent, item, voteInfo.getErrorContent());
            return null;
        }
        return voteInfo;
    }

    public boolean isManualVoteResult(GitHubQueryContext qc, VoteConfig votingConfig, DataCommonComment comment) {
        return comment.body.contains(MANUAL_VOTE_RESULT)
                && ctx.getTeamMembershipService().isLoginIncluded(qc, comment.author.login, votingConfig.managers);
    }

    private void sendVotingErrorEmail(GitHubQueryContext qc, VoteConfig votingConfig,
            DataCommonItem item, VoteEvent voteEvent, Throwable e) {
        // If configured to do so, email the error_email_address
        if (votingConfig.sendErrorEmail()) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            if (e != null) {
                e.printStackTrace(pw);
            }

            String subject = "Voting error occurred with " + voteEvent.getRepoFullName() + " #" + voteEvent.getNumber();

            String messageBody = sw.toString();
            String htmlBody = messageBody.replace("\n", "<br/>\n");

            MailTemplateInstance mail = Templates.votingErrorEvent(item,
                    voteEvent,
                    "Voting Error: " + e,
                    messageBody,
                    htmlBody,
                    Instant.now().toString());

            mailer.sendEmail(qc.getLogId(), subject, mail, votingConfig.errorEmailAddress);
        }
    }

    public VoteEvent createVoteEvent(EventData eventData) {
        return new VoteEvent(
                eventData.getInstallationId(),
                eventData.getRepoFullName(),
                VoteEvent.eventToType(eventData),
                eventData.getNodeId(),
                eventData.getNumber());
    }

    void scheduleQueryRepository(long installationId, String repoFullName) {
        // allow full repo queries to collapse/merge
        periodicUpdate.queueReconciliation(repoFullName, () -> {
            VoteConfig voteConfig = ctx.getVoteConfig(repoFullName);
            if (voteConfig.isDisabled()) {
                return;
            }
            ScopedQueryContext qc = new ScopedQueryContext(ctx, installationId, repoFullName);
            List<DataDiscussion> discussions = qc.findDiscussionsWithLabel("vote/open");
            if (discussions == null || qc.hasErrors()) {
                handleErrors(qc, repoFullName, "unable to fetch discussions",
                        () -> scheduleQueryRepository(installationId, repoFullName));
                return;
            }
            for (var discussion : discussions) {
                scheduleQueryItem(qc, repoFullName, discussion, EventType.discussion);
            }

            List<DataCommonItem> issues = qc.findIssuesWithLabel("vote/open");
            if (issues == null || qc.hasErrors()) {
                handleErrors(qc, repoFullName, "unable to fetch issues",
                        () -> scheduleQueryRepository(installationId, repoFullName));
                return;
            }
            for (var issue : issues) {
                scheduleQueryItem(qc, repoFullName,
                        issue,
                        issue.isPullRequest ? EventType.pull_request : EventType.issue);
            }
        });
    }

    private void scheduleQueryItem(ScopedQueryContext qc, String repoFullName,
            DataCommonItem item, EventType eventType) {
        VoteEvent voteEvent = new VoteEvent(
                qc.getInstallationId(), repoFullName, eventType, item.id, item.number);

        // allow vote counting to collapse/merge
        reconcileVoteEvent(voteEvent);
    }

    private void handleErrors(GitHubQueryContext qc, String taskGroup, String message, Runnable retry) {
        if (qc.hasRetriableNetworkError()) {
            Log.debugf("[%s] retriable network error: %s", qc.getLogId(), message);
            periodicUpdate.scheduleReconciliationRetry(taskGroup,
                    (retryCount) -> retry.run(),
                    0); // Start with retry count 0
            return;
        }
        if (qc.hasErrors()) {
            qc.logAndSendContextErrors(message);
            return;
        }
        Log.errorf("[%s] unknown error: %s", qc.getLogId(), message);
    }
}
