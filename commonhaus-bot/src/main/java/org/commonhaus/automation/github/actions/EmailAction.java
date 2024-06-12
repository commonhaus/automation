package org.commonhaus.automation.github.actions;

import java.util.List;

import org.commonhaus.automation.github.EventQueryContext;
import org.commonhaus.automation.github.context.DataCommonItem;
import org.commonhaus.automation.github.context.DataDiscussion;
import org.commonhaus.automation.github.context.EventData;
import org.commonhaus.automation.github.context.EventPayload;
import org.commonhaus.automation.mail.MailEvent;
import org.commonhaus.automation.markdown.MarkdownConverter;

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
    @CheckedTemplate
    static class Templates {
        public static native MailTemplateInstance itemEvent(String title, String htmlBody,
                DataCommonItem item, String repoSlug, String status);
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
    public void apply(EventQueryContext qc) {
        EventData eventData = qc.getEventData();

        Log.debugf("[%s] EmailAction.apply: Preparing email to %s",
                eventData.getLogId(), List.of(addresses));

        final String title;
        final String status = qc.getStatus();
        MailTemplateInstance mailTemplateInstance;
        try {
            mailTemplateInstance = switch (qc.getEventType()) {
                case issue, pull_request -> {
                    EventPayload.CommonItemPayload payload = eventData.getEventPayload();
                    DataCommonItem item = payload.issue == null
                            ? payload.pullRequest
                            : payload.issue;

                    title = String.format("PR #%s %s",
                            item.number, item.title);

                    yield Templates.itemEvent(title,
                            toHtmlBody(eventData, item.body),
                            item,
                            eventData.getRepository().getFullName(),
                            status);
                }
                case discussion -> {
                    EventPayload.DiscussionPayload payload = eventData.getEventPayload();
                    DataDiscussion discussion = payload.discussion;

                    title = String.format("#%s %s",
                            discussion.number, discussion.title);

                    yield Templates.itemEvent(title,
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
            MailEvent mailEvent = new MailEvent(eventData.getLogId(),
                    mailTemplateInstance, subject, addresses);
            Arc.container().instance(EventBus.class).get().send(MailEvent.ADDRESS, mailEvent);
        }
    }

    private String toHtmlBody(EventData eventData, String body) {
        return body == null
                ? "<p></p>"
                : MarkdownConverter.toHtml(body.replaceAll("<!--.*?-->", ""));
    }
}
