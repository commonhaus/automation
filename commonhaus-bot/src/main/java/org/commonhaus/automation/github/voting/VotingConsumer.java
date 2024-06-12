package org.commonhaus.automation.github.voting;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.commonhaus.automation.github.context.BotComment;
import org.commonhaus.automation.github.context.DataCommonComment;
import org.commonhaus.automation.github.context.DataLabel;
import org.commonhaus.automation.github.context.DataReaction;
import org.commonhaus.automation.github.context.QueryCache;
import org.commonhaus.automation.github.context.QueryContext;
import org.commonhaus.automation.github.rules.MatchLabel;
import org.commonhaus.automation.github.voting.VoteEvent.ManualVoteEvent;
import org.commonhaus.automation.mail.MailEvent;
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
    private static final QueryCache VOTE_CHECK = QueryCache.create(
            "VOTE_CHECK", b -> b.expireAfterAccess(6, TimeUnit.HOURS));

    public static final String VOTE_DONE = "vote/done";
    public static final String VOTE_OPEN = "vote/open";
    public static final String VOTE_PROCEED = "vote/proceed";
    public static final String VOTE_QUORUM = "vote/quorum";
    public static final String VOTE_REVISE = "vote/revise";

    static final MatchLabel OPEN_VOTE = new MatchLabel(List.of(VOTE_OPEN));

    @CheckedTemplate
    static class Templates {
        public static native MailTemplateInstance votingErrorEvent(VoteEvent voteEvent, String title, String body,
                String htmlBody);
    }

    @Inject
    ObjectMapper objectMapper;

    @Inject
    EventBus bus;

    @ConsumeEvent(VoteEvent.ADDRESS)
    @Blocking
    public void consume(Message<VoteEvent> msg) {
        VoteEvent voteEvent = msg.body();
        QueryContext qc = voteEvent.getQueryContext();
        VoteConfig votingConfig = voteEvent.getVotingConfig();

        AtomicBoolean checkRunning = VOTE_CHECK.computeIfAbsent(voteEvent.getId(), (k) -> new AtomicBoolean(false));
        boolean iAmVoteCounter = checkRunning.compareAndSet(false, true);

        try {
            if (iAmVoteCounter && repoHasLabels(voteEvent) && OPEN_VOTE.matches(qc, voteEvent.getId())) {
                final List<DataCommonComment> resultComments = voteEvent.itemIsClosed()
                        ? findResultComments(voteEvent)
                        : List.of();
                countVotes(qc, voteEvent, votingConfig, resultComments);
            } else if (!iAmVoteCounter) {
                Log.debugf("[%s] voting.checkVotes: skip event (running)", voteEvent.getLogId());
            }
        } catch (Throwable e) {
            Log.errorf(e, "[%s] voting.checkVotes: unexpected error", voteEvent.getLogId());
            sendErrorEmail(votingConfig, voteEvent, e);
        } finally {
            if (iAmVoteCounter) {
                checkRunning.set(false);
            }
        }
    }

    @ConsumeEvent(VoteEvent.MANUAL_ADDRESS)
    @Blocking
    public void consumeManualResult(Message<ManualVoteEvent> msg) {
        ManualVoteEvent voteEvent = msg.body();
        VoteConfig votingConfig = voteEvent.getVotingConfig();

        QueryContext qc = voteEvent.getQueryContext();
        qc.refreshConnection(); // reauthenticate if necessary

        AtomicBoolean checkRunning = VOTE_CHECK.computeIfAbsent(voteEvent.getId(), (k) -> new AtomicBoolean(false));
        boolean iAmVoteCounter = checkRunning.compareAndSet(false, true);

        try {
            if (iAmVoteCounter && repoHasLabels(voteEvent)) {
                countVotes(qc, voteEvent, votingConfig, List.of(voteEvent.getComment()));
            } else if (!iAmVoteCounter) {
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(2));
                } catch (InterruptedException e) {
                }
                // re-queue the message
                Log.infof("[%s] voting.consumeManualResult: re-queue event", qc.getLogId());
                bus.send(VoteEvent.MANUAL_ADDRESS, voteEvent);
            }
        } catch (Throwable e) {
            Log.errorf(e, "[%s] voting.checkVotes: unexpected error", voteEvent.getLogId());
            sendErrorEmail(votingConfig, voteEvent, e);
        } finally {
            if (iAmVoteCounter) {
                checkRunning.set(false);
            }
        }
    }

    private void countVotes(QueryContext qc, VoteEvent voteEvent, VoteConfig votingConfig,
            List<DataCommonComment> resultComments) {
        Log.debugf("[%s] countVotes: checking open vote (%s, %s)", voteEvent.getLogId(),
                votingConfig.errorEmailAddress == null ? "no error emails"
                        : List.of(votingConfig.errorEmailAddress),
                votingConfig.voteThreshold);

        VoteInformation voteInfo = getVoteInformation(voteEvent);
        if (voteInfo == null) {
            return;
        }

        Log.debugf("[%s] checkOpenVote: counting votes using %s", voteEvent.getLogId(), voteInfo.voteType);

        final List<DataReaction> reactions = voteInfo.countComments()
                ? List.of()
                : getFilteredReactions(voteEvent);
        final List<DataCommonComment> comments = voteInfo.countComments()
                ? getFilteredComments(voteEvent)
                : List.of();

        // Tally the votes
        VoteTally tally = new VoteTally(voteInfo, reactions, comments, resultComments);

        // Add or update a bot comment summarizing the vote.
        String commentBody = tally.toMarkdown(voteEvent.itemIsClosed());
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
        if (tally.isDone) {
            // remove the open label first so we don't process votes again
            qc.removeLabels(voteEvent.getId(), List.of(VOTE_OPEN));
            qc.addLabel(voteEvent.getId(), VOTE_DONE);
        }
    }

    // Make sure other labels are present
    private boolean repoHasLabels(VoteEvent voteEvent) {
        QueryContext qc = voteEvent.getQueryContext();
        List<String> requiredLabels = List.of(VOTE_DONE, VOTE_PROCEED, VOTE_REVISE, VOTE_QUORUM);
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

    private List<DataCommonComment> getFilteredComments(VoteEvent voteEvent) {
        QueryContext qc = voteEvent.getQueryContext();
        // Skip all bot comments
        List<DataCommonComment> comments = qc.getComments(voteEvent.getId(),
                x -> !qc.isBot(x.author.login));
        return comments;
    }

    private List<DataCommonComment> findResultComments(VoteEvent voteEvent) {
        QueryContext qc = voteEvent.getQueryContext();
        List<DataCommonComment> comments = qc.getComments(voteEvent.getId(),
                x -> VoteEvent.isManualVoteResult(qc, voteEvent.getVotingConfig(), x));
        return comments;
    }

    private List<DataReaction> getFilteredReactions(VoteEvent voteEvent) {
        QueryContext qc = voteEvent.getQueryContext();

        // GraphQL fetch of all reactions on item (return if none)
        // Could query by group first, but pagination happens either way.
        List<DataReaction> reactions = qc.getReactions(voteEvent.getId());

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
        return reactions;
    }

    private void updateBotComment(VoteEvent voteEvent, String commentBody) {
        QueryContext qc = voteEvent.getQueryContext();
        BotComment comment = qc.updateBotComment(voteEvent.commentPattern(),
                voteEvent.getItemType(), voteEvent.getId(), commentBody, voteEvent.getBody());
        if (comment != null) {
            // Use informaton from the existing comment to construct replacement text
            String newBody = voteEvent.updateItemText(comment);
            if (newBody.equals(voteEvent.getBody())) {
                Log.debugf("[%s] voting.checkVotes: item description unchanged", voteEvent.getLogId());
            } else {
                qc.updateItemDescription(voteEvent.getItemType(), voteEvent.getId(), newBody);
            }
        }
    }

    private void sendErrorEmail(VoteConfig votingConfig, VoteEvent voteEvent, Throwable e) {
        // If configured to do so, email the error_email_address
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

            bus.send(MailEvent.ADDRESS, new MailEvent(voteEvent.getLogId(),
                    mail, subject, votingConfig.errorEmailAddress));
        }
    }
}
