package org.commonhaus.automation.github.actions;

import java.util.List;

import org.commonhaus.automation.github.EventData;
import org.commonhaus.automation.github.model.DataDiscussion;
import org.commonhaus.automation.github.model.EventPayload;
import org.commonhaus.automation.github.model.EventQueryContext;
import org.commonhaus.automation.mail.MailConsumer;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHPullRequest;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import io.quarkus.arc.Arc;
import io.quarkus.logging.Log;
import io.quarkus.mailer.MailTemplate.MailTemplateInstance;
import io.quarkus.qute.CheckedTemplate;
import io.vertx.mutiny.core.eventbus.EventBus;

@JsonDeserialize(as = EmailAction.class)
public class EmailAction extends Action {
    static final Parser parser = Parser.builder().build();
    static final HtmlRenderer renderer = HtmlRenderer.builder().build();

    @CheckedTemplate
    static class Templates {
        public static native MailTemplateInstance pullRequestEvent(String title, String htmlBody,
                GHPullRequest pullRequest, String status);

        public static native MailTemplateInstance discussionEvent(String title, String htmlBody,
                DataDiscussion discussion, String repoSlug, String status);
    }

    @JsonProperty
    String[] addresses;

    public EmailAction() {
    }

    public EmailAction(JsonNode address) {
        if (address.isArray()) {
            this.addresses = new String[address.size()];
            for (int i = 0; i < address.size(); i++) {
                this.addresses[i] = address.get(i).asText();
            }
        } else {
            this.addresses = new String[] { address.asText() };
        }
    }

    @Override
    public void apply(EventQueryContext queryContext) {
        EventData eventData = queryContext.getEventData();

        Log.debugf("[%s] EmailAction.apply: Preparing email to %s",
                eventData.getLogId(), List.of(addresses));

        final String title;
        final String status = queryContext.getStatus();
        MailTemplateInstance mailTemplateInstance;
        try {
            mailTemplateInstance = switch (queryContext.getEventType()) {
                case pull_request -> {
                    GHEventPayload.PullRequest payload = eventData.getGHEventPayload();

                    title = String.format("PR #%s %s",
                            payload.getNumber(), payload.getPullRequest().getTitle());

                    yield Templates.pullRequestEvent(title,
                            toHtmlBody(eventData, payload.getPullRequest().getBody()),
                            payload.getPullRequest(),
                            status);
                }
                case discussion -> {
                    EventPayload.DiscussionPayload payload = eventData.getEventPayload();
                    DataDiscussion discussion = payload.discussion;

                    title = String.format("#%s %s",
                            discussion.number, discussion.title);

                    yield Templates.discussionEvent(title,
                            toHtmlBody(eventData, discussion.body),
                            discussion,
                            eventData.getRepository().getFullName(),
                            status);
                }
                default -> {
                    Log.warnf("[%s] EmailAction.apply: unsupported event type", eventData.getLogId());
                    title = null;
                    yield null;
                }
            };
        } catch (Exception e) {
            Log.errorf(e, "[%s] EmailAction.apply: Failed to prepare email to %s", eventData.getLogId(), List.of(addresses));
            return;
        }

        String subject = "(" + status + ") " + title;

        if (mailTemplateInstance != null) {
            Log.debugf("[%s] EmailAction.apply: Sending email to %s; %s", eventData.getLogId(), List.of(addresses), subject);
            MailConsumer.MailEvent mailEvent = new MailConsumer.MailEvent(eventData.getLogId(),
                    mailTemplateInstance, subject, addresses);
            Arc.container().instance(EventBus.class).get().send("mail", mailEvent);
        }
    }

    private String toHtmlBody(EventData eventData, String body) {
        if (body == null) {
            return "<p></p>";
        }
        try {
            Node document = parser.parse(body);
            return renderer.render(document);
        } catch (Exception e) {
            Log.errorf(e, "[%s] EmailAction.toHtmlBody: Failed to render body as HTML", eventData.getLogId());
            return body;
        }
    }
}
