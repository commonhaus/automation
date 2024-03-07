package org.commonhaus.automation.github.voting;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.commonhaus.automation.github.Voting;
import org.commonhaus.automation.github.model.DataActor;
import org.commonhaus.automation.github.model.DataCommonComment;
import org.commonhaus.automation.github.model.DataLabel;
import org.commonhaus.automation.github.model.DataReaction;
import org.commonhaus.automation.github.model.QueryCache;
import org.commonhaus.automation.github.model.QueryHelper;
import org.commonhaus.automation.github.model.QueryHelper.QueryContext;
import org.commonhaus.automation.github.rules.MatchLabel;
import org.commonhaus.automation.mail.MailConsumer;
import org.kohsuke.github.ReactionContent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.logging.Log;
import io.quarkus.mailer.MailTemplate.MailTemplateInstance;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.Blocking;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.core.eventbus.Message;

@ApplicationScoped
public class VotingConsumer {

    public static class CheckStatus {
        private Instant lastCheck;
        private final AtomicBoolean running = new AtomicBoolean(false);

        public CheckStatus() {
        }

        public boolean startScheduledUpdate() {
            // Don't check more than once every 15 minutes
            if (lastCheck != null && lastCheck.plus(15, ChronoUnit.MINUTES).isBefore(Instant.now())) {
                return false;
            }
            return running.compareAndSet(false, true);
        }

        public boolean startUpdate(VoteEvent voteEvent) {
            if (voteEvent.isScheduled()) {
                return startScheduledUpdate();
            }
            return running.compareAndSet(false, true);
        }

        public void finishUpdate() {
            lastCheck = Instant.now();
            running.set(false);
        }
    }

    public static final String VOTE_DONE = "vote/done";
    public static final String VOTE_OPEN = "vote/open";
    public static final String VOTE_PROCEED = "vote/proceed";
    public static final String VOTE_QUORUM = "vote/quorum";
    public static final String VOTE_REVISE = "vote/revise";

    static final MatchLabel OPEN_VOTE = new MatchLabel(List.of(VOTE_OPEN));
    static final MatchLabel FINISH_VOTE = new MatchLabel(List.of(VOTE_DONE, "!" + VOTE_PROCEED, "!" + VOTE_REVISE));

    static final Pattern botCommentPattern = Pattern.compile(
            "Progress tracked \\[below\\]\\(([^ )]+) ?(?:\"([^\"]+)\")?\\)\\.",
            Pattern.CASE_INSENSITIVE);

    @CheckedTemplate
    static class Templates {
        public static native MailTemplateInstance votingErrorEvent(VoteEvent voteEvent, String title, String body,
                String htmlBody);
    }

    @Inject
    ObjectMapper objectMapper;

    @Inject
    QueryHelper queryHelper;

    @Inject
    EventBus bus;

    @ConsumeEvent("voting")
    @Blocking
    public void consume(Message<VoteEvent> msg) {
        try {
            checkVotes(msg);
        } finally {
            msg.reply(null);
        }
    }

    private void checkVotes(Message<VoteEvent> msg) {
        VoteEvent voteEvent = msg.body();
        QueryContext qc = voteEvent.getQueryContext();
        Voting.Config votingConfig = voteEvent.getVotingConfig();

        // Each query context does have a reference to the original event and to the
        // potentially short-lived GitHub connection. Reauthenticate if
        // necessary/possible
        if (!qc.isCredentialValid() && !qc.reauthenticate()) {
            Log.infof("[%s] voting.checkVotes: GitHub connection expired and can't be renewed", qc.getLogId());
            return;
        }

        CheckStatus checkStatus = QueryCache.RECENT_VOTE_CHECK.computeIfAbsent(
                voteEvent.getId(), (k) -> new CheckStatus());

        Log.debugf("[%s] voting.checkVotes: process event (running=%s)", voteEvent.getLogId(), checkStatus.running.get());
        if (!checkStatus.startUpdate(voteEvent)) {
            return;
        }
        Log.debugf("[%s] voting.checkVotes: process event", voteEvent.getLogId());
        try {
            if (!repoHasLabels(voteEvent)) {
                return;
            }
            if (OPEN_VOTE.matches(qc, voteEvent.getId())) {
                checkOpenVote(qc, voteEvent, votingConfig);
            }
            if (FINISH_VOTE.matches(qc, voteEvent.getId())) {
                finishVote(qc, voteEvent, votingConfig);
            }
        } catch (RuntimeException e) {
            Log.errorf(e, "[%s] voting.checkVotes: unexpected error", voteEvent.getLogId());
            sendErrorEmail(votingConfig, voteEvent, e);
        } finally {
            checkStatus.finishUpdate();
        }
    }

    private void finishVote(QueryContext qc, VoteEvent voteEvent, Voting.Config votingConfig) {
        String logId = "[" + voteEvent.getLogId() + "] finishVote";
    }

    private void checkOpenVote(QueryContext qc, VoteEvent voteEvent, Voting.Config votingConfig) {
        Log.debugf("[%s] checkOpenVote: checking open vote (%s, %s)", voteEvent.getLogId(),
                List.of(votingConfig.error_email_address), votingConfig.votingThreshold);

        VoteInformation voteInfo = getVoteInformation(voteEvent);
        if (voteInfo == null) {
            return;
        }

        Log.debugf("[%s] checkOpenVote: counting votes using %s", voteEvent.getLogId(), voteInfo.voteType);

        final Collection<DataReaction> reactions = voteInfo.countComments()
                ? List.of()
                : getFilteredReactions(voteEvent);
        final Collection<DataCommonComment> comments = voteInfo.countComments()
                ? getFilteredComments(voteEvent)
                : List.of();
        if (reactions.isEmpty() && comments.isEmpty()) {
            return;
        }

        List<DataActor> logins = getTeamLogins(voteEvent, voteInfo);

        // Tally the votes
        VoteTally tally = new VoteTally(voteInfo, reactions, comments, logins);

        // Add or update a bot comment summarizing the vote.
        String commentBody = tally.toMarkdown();
        try {
            String jsonData = objectMapper.writeValueAsString(tally);
            commentBody += "\r\n<!-- vote::data " + jsonData + " -->";
        } catch (JsonProcessingException e) {
            Log.errorf(e, "[%s] voting.checkVotes: unable to serialize voting data", voteEvent.getLogId());
            sendErrorEmail(votingConfig, voteEvent, e);
        }
        updateBotComment(voteEvent, commentBody);

        if (tally.hasQuorum) {
            qc.addLabel(voteEvent.getId(), VOTE_QUORUM);
        }
    }

    // Make sure other labels are present
    private boolean repoHasLabels(VoteEvent voteEvent) {
        QueryContext qc = voteEvent.getQueryContext();
        List<String> requiredLabels = List.of(VOTE_DONE, VOTE_PROCEED, VOTE_REVISE);
        Collection<DataLabel> voteLabels = qc.findLabels(requiredLabels);

        if (voteLabels.size() != requiredLabels.size()) {
            qc.addBotReaction(voteEvent.getId(), ReactionContent.CONFUSED);
            String comment = "The following labels must be defined in this repository:\r\n"
                    + String.join(", ", requiredLabels) + "\r\n\r\n"
                    + "Please ensure all labels have been defined.";
            updateBotComment(voteEvent, comment);
            return false;
        }
        return true;
    }

    // Get information about vote mechanics (return if bad data)
    private VoteInformation getVoteInformation(VoteEvent voteEvent) {
        QueryContext qc = voteEvent.getQueryContext();
        final VoteInformation voteInfo = new VoteInformation(voteEvent);
        if (!voteInfo.isValid()) {
            qc.addBotReaction(voteEvent.getId(), ReactionContent.CONFUSED);
            Log.debugf("[%s] voting.checkVotes: invalid vote information -- %s", voteEvent.getLogId(),
                    voteInfo.getErrorContent());

            // Add or update a bot comment summarizing what went wrong.
            updateBotComment(voteEvent, voteInfo.getErrorContent());
            return null;
        }
        return new VoteInformation(voteEvent);
    }

    private Collection<DataCommonComment> getFilteredComments(VoteEvent voteEvent) {
        QueryContext qc = voteEvent.getQueryContext();

        Collection<DataCommonComment> comments = qc.getComments(voteEvent.getId());

        // The bot's votes/comments never count.
        comments.removeIf(x -> qc.isBot(x.author.login));

        if (comments.isEmpty()) {
            updateBotComment(voteEvent, "No votes (non-bot comments) found on this item.");
        }
        return comments;
    }

    private Collection<DataReaction> getFilteredReactions(VoteEvent voteEvent) {
        QueryContext qc = voteEvent.getQueryContext();

        // GraphQL fetch of all reactions on item (return if none)
        // Could query by group first, but pagination happens either way.
        Collection<DataReaction> reactions = qc.getReactions(voteEvent.getId());

        // The bot's votes never count.
        reactions.removeIf(x -> {
            if (qc.isBot(x.user.login)) {
                if (x.reactionContent == ReactionContent.CONFUSED) {
                    // If we were previously confused, we aren't anymore.
                    qc.removeBotReaction(voteEvent.getId(), ReactionContent.CONFUSED);
                }
                return true;
            }
            return false;
        });

        if (reactions.isEmpty()) {
            updateBotComment(voteEvent, "No votes (reactions) found on this item.");
        }
        return reactions;
    }

    private void updateBotComment(VoteEvent voteEvent, String commentBody) {
        QueryContext qc = voteEvent.getQueryContext();
        if (qc.hasErrors()) {
            return;
        }

        String bodyString = voteEvent.getBody();
        Integer existingId = commentIdFromBody(bodyString);
        DataCommonComment comment = qc.updateBotComment(voteEvent.getEventType(), voteEvent.getId(), commentBody,
                existingId);
        if (comment != null && (existingId == null || !existingId.equals(comment.databaseId))) {
            String prefix = String.format("Progress tracked [below](%s \"%s\").",
                    comment.url, comment.databaseId);
            Matcher matcher = botCommentPattern.matcher(bodyString);
            if (matcher.find()) {
                bodyString = matcher.replaceFirst(prefix);
            } else {
                bodyString = prefix + "\r\n\r\n" + bodyString;
            }
            qc.updateItemDescription(voteEvent.getEventType(), voteEvent.getId(), bodyString);
        }
    }

    private Integer commentIdFromBody(String body) {
        Matcher matcher = botCommentPattern.matcher(body);
        if (matcher.find()) {
            if (matcher.group(2) != null) {
                return Integer.parseInt(matcher.group(2));
            }
            int pos = matcher.group(1).lastIndexOf("-");
            if (pos > 0) {
                return Integer.parseInt(matcher.group(1).substring(pos + 1));
            }
        }
        return null;
    }

    private List<DataActor> getTeamLogins(VoteEvent voteEvent, VoteInformation voteInfo) {
        QueryContext qc = voteEvent.getQueryContext();
        List<DataActor> logins = QueryCache.TEAM_LIST.getCachedValue(voteInfo.group);
        if (logins == null) {
            logins = qc.execGitHubSync((gh, dryRun) -> voteInfo.team.getMembers().stream().map(DataActor::new).toList());
            QueryCache.TEAM_LIST.putCachedValue(voteInfo.group, logins);
        }
        return logins;
    }

    private void sendErrorEmail(Voting.Config votingConfig, VoteEvent voteEvent, Exception e) {
        // If configured to do so, send an email to the error_email_address
        if (votingConfig.sendErrorEmail()) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);

            String subject = "Voting error occurred with " + voteEvent.getRepoSlug() + " #" + voteEvent.getNumber();

            String messageBody = sw.toString();
            String htmlBody = messageBody.replace("\n", "<br/>\n");

            MailTemplateInstance mail = Templates.votingErrorEvent(voteEvent,
                    "Voting Error: " + e,
                    messageBody,
                    htmlBody);

            bus.requestAndForget("mail", new MailConsumer.MailEvent(voteEvent.getLogId(),
                    mail, subject, votingConfig.error_email_address));
        }
    }
}
