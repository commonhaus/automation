package org.commonhaus.automation.github.voting;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.commonhaus.automation.github.EventData;
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

    public static final String VOTE_DONE = "vote/done";
    public static final String VOTE_OPEN = "vote/open";
    public static final String VOTE_PROCEED = "vote/proceed";
    public static final String VOTE_QUORUM = "vote/quorum";
    public static final String VOTE_REVISE = "vote/revise";

    static final MatchLabel OPEN_VOTE = new MatchLabel(List.of(VOTE_OPEN));
    static final MatchLabel FINISH_VOTE = new MatchLabel(List.of(VOTE_DONE, "!" + VOTE_PROCEED, "!" + VOTE_REVISE));

    static final Pattern botCommentPattern = Pattern.compile("Progress tracked \\[below\\]\\(([^ )]+) ?(?:\"([^\"]+)\")?\\)\\.",
            Pattern.CASE_INSENSITIVE);

    @CheckedTemplate
    static class Templates {
        public static native MailTemplateInstance votingErrorEvent(EventData eventData, String title, String body,
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
        VoteEvent voteEvent = msg.body();
        QueryContext qc = voteEvent.qc;
        EventData eventData = qc.getEventData();
        Voting.Config votingConfig = voteEvent.votingConfig;

        // Each query context does have a reference to the original event and to the
        // potentially short-lived GitHub connection. Reauthenticate if
        // necessary/possible
        if (!qc.isCredentialValid()) {
            Log.infof("[%s] voting.checkVotes: GitHub connection expired and can't be renewed", qc.getLogId());
            return;
        }
        Log.debugf("[%s] voting.checkVotes: process event", qc.getLogId());
        try {
            if (OPEN_VOTE.matches(qc)) {
                checkOpenVote(qc, eventData, votingConfig);
            }
            if (FINISH_VOTE.matches(qc)) {
                finishVote(qc, eventData, votingConfig);
            }
        } catch (RuntimeException e) {
            Log.errorf(e, "[%s] voting.checkVotes: unexpected error", qc.getLogId());
            sendErrorEmail(votingConfig, eventData, e);
        }

        // nothing to do
        msg.reply(null);
    }

    private void finishVote(QueryContext qc, EventData eventData, Voting.Config votingConfig) {
        String logId = "[" + qc.getLogId() + "] finishVote";
    }

    private void checkOpenVote(QueryContext qc, EventData eventData, Voting.Config votingConfig) {
        Log.debugf("[%s] checkOpenVote: checking open vote (%s, %s)", qc.getLogId(),
                votingConfig.error_email_address, votingConfig.votingThreshold);

        // Make sure other labels are present
        List<String> requiredLabels = List.of(VOTE_DONE, VOTE_PROCEED, VOTE_REVISE);
        Collection<DataLabel> voteLabels = qc.findLabels(eventData, requiredLabels);
        if (voteLabels.size() != requiredLabels.size()) {
            qc.addBotReaction(ReactionContent.CONFUSED);
            String comment = "The following labels must be defined in this repository:\r\n"
                    + String.join(", ", requiredLabels) + "\r\n\r\n"
                    + "Please ensure all labels have been defined.";
            updateBotComment(qc, comment);
            return;
        }

        // Get information about vote mechanics (return if bad data)
        final VoteInformation voteInfo = new VoteInformation(qc, eventData.getBody(), votingConfig);
        if (!voteInfo.isValid()) {
            qc.addBotReaction(ReactionContent.CONFUSED);

            // Add or update a bot comment summarizing what went wrong.
            updateBotComment(qc, voteInfo.getErrorContent());
            return;
        }

        final Collection<DataReaction> reactions;
        final Collection<DataCommonComment> comments;

        if (voteInfo.countComments()) {
            reactions = List.of();
            comments = qc.getComments();

            // The bot's votes/comments never count.
            comments.removeIf(x -> qc.isBot(x.author.login));

            updateBotComment(qc, "No votes (non-bot comments) found on this item.");
            return;
        } else {
            comments = List.of();
            // GraphQL fetch of all reactions on item (return if none)
            // Could query by group first, but pagination happens either way.
            reactions = qc.getReactions();

            // The bot's votes never count.
            reactions.removeIf(x -> {
                if (qc.isBot(x.user.login)) {
                    if (x.reactionContent == ReactionContent.CONFUSED) {
                        // If we were previously confused, we aren't anymore.
                        qc.removeBotReaction(ReactionContent.CONFUSED);
                    }
                    return true;
                }
                return false;
            });

            if (reactions.isEmpty()) {
                updateBotComment(qc, "No votes (reactions) found on this item.");
                return;
            }
        }

        List<DataActor> logins = getTeamLogins(qc, voteInfo);
        VoteTally tally = new VoteTally(voteInfo, reactions, comments, logins);

        // Add or update a bot comment summarizing the vote.
        String commentBody = tally.toMarkdown();
        try {
            String jsonData = objectMapper.writeValueAsString(tally);
            commentBody += "\r\n<!-- vote::data " + jsonData + " -->";
        } catch (JsonProcessingException e) {
            Log.errorf(e, "[%s] voting.checkVotes: unable to serialize voting data", qc.getLogId());
            sendErrorEmail(votingConfig, eventData, e);
        }
        updateBotComment(qc, commentBody);

        if (tally.hasQuorum) {
            qc.addLabel(eventData, VOTE_QUORUM);
        }
    }

    private void updateBotComment(QueryContext qc, String commentBody) {
        String bodyString = qc.getEventData().getBody();
        Integer existingId = commentIdFromBody(bodyString);
        DataCommonComment comment = qc.updateBotComment(commentBody, existingId);
        if (comment != null && (existingId == null || !existingId.equals(comment.databaseId))) {
            String prefix = String.format("Progress tracked [below](%s \"%s\").",
                    comment.url, comment.databaseId);
            Matcher matcher = botCommentPattern.matcher(bodyString);
            if (matcher.find()) {
                bodyString = matcher.replaceFirst(prefix);
            } else {
                bodyString = prefix + "\r\n\r\n" + bodyString;
            }
            qc.updateItemDescription(bodyString);
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

    private List<DataActor> getTeamLogins(QueryContext qc, VoteInformation voteInfo) {
        List<DataActor> logins = QueryCache.TEAM_LIST.getCachedValue(voteInfo.group);
        if (logins == null) {
            logins = qc.execGitHubSync((gh, dryRun) -> voteInfo.team.getMembers().stream().map(DataActor::new).toList());
            QueryCache.TEAM_LIST.putCachedValue(voteInfo.group, logins);
        }
        return logins;
    }

    private void sendErrorEmail(Voting.Config votingConfig, EventData eventData, Exception e) {
        // If configured to do so, send an email to the error_email_address
        if (votingConfig.sendErrorEmail()) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);

            String subject = "Voting error occurred with " + eventData.getRepoSlug() + " #" + eventData.getNumber();

            String messageBody = sw.toString();
            String htmlBody = messageBody.replace("\n", "<br/>\n");

            MailTemplateInstance mail = Templates.votingErrorEvent(eventData,
                    "Voting Error: " + e,
                    messageBody,
                    htmlBody);

            bus.requestAndForget("mail", new MailConsumer.MailEvent(eventData.getLogId(),
                    mail, subject, votingConfig.error_email_address));
        }
    }
}