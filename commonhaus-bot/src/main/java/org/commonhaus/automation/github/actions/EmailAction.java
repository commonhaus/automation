package org.commonhaus.automation.github.actions;

import java.util.List;

import org.commonhaus.automation.github.EventQueryContext;
import org.commonhaus.automation.github.context.DataCommonComment;
import org.commonhaus.automation.github.context.DataCommonItem;
import org.commonhaus.automation.github.context.DataDiscussion;
import org.commonhaus.automation.github.context.DataDiscussionComment;
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
        public static native MailTemplateInstance commentEvent(String title, String htmlBody,
                DataCommonItem item, DataCommonComment comment, String repoSlug);

        public static native MailTemplateInstance itemEvent(String title, String htmlBody,
                DataCommonItem item, String repoSlug);
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
        String status = qc.getStatus();
        if ("created".equals(status)) {
            status = "";
        } else {
            status = "(" + status + ") ";
        }

        MailTemplateInstance mailTemplateInstance;
        try {
            mailTemplateInstance = switch (qc.getEventType()) {
                case issue, pull_request -> {
                    EventPayload.CommonItemPayload payload = eventData.getEventPayload();
                    DataCommonItem item = payload.issue == null
                            ? payload.pullRequest
                            : payload.issue;

                    title = String.format("%s%s#%s %s",
                            status,
                            payload.issue == null ? "PR " : "",
                            item.number, item.title);

                    yield Templates.itemEvent(title,
                            toHtmlBody(item.body),
                            item,
                            eventData.getRepository().getFullName());
                }
                case issue_comment -> {
                    EventPayload.CommonItemCommentPayload payload = eventData.getEventPayload();
                    DataCommonItem item = payload.issue == null
                            ? payload.pullRequest
                            : payload.issue;
                    DataCommonComment comment = payload.comment;

                    title = String.format("← %s - %s#%s %s",
                            comment.author.login,
                            payload.issue == null ? "PR " : "",
                            item.number, item.title);

                    yield Templates.commentEvent(title,
                            toHtmlBody(comment.body),
                            item,
                            comment,
                            eventData.getRepository().getFullName());
                }
                case discussion -> {
                    EventPayload.DiscussionPayload payload = eventData.getEventPayload();
                    DataDiscussion discussion = payload.discussion;

                    title = String.format("%s#%s %s",
                            status,
                            discussion.number, discussion.title);

                    yield Templates.itemEvent(title,
                            toHtmlBody(discussion.body),
                            discussion,
                            eventData.getRepository().getFullName());
                }
                case discussion_comment -> {
                    EventPayload.DiscussionCommentPayload payload = eventData.getEventPayload();
                    DataDiscussion discussion = payload.discussion;
                    DataDiscussionComment comment = payload.comment;

                    title = String.format("↵ %s - #%s %s",
                            comment.author.login, discussion.number, discussion.title);

                    yield Templates.commentEvent(title,
                            toHtmlBody(comment.body),
                            discussion,
                            comment,
                            eventData.getRepository().getFullName());
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

        String subject = title;

        if (mailTemplateInstance != null) {
            Log.debugf("[%s] EmailAction.apply: Sending email to %s; %s", eventData.getLogId(), List.of(addresses), subject);
            MailEvent mailEvent = new MailEvent(eventData.getLogId(),
                    mailTemplateInstance, subject, addresses);
            Arc.container().instance(EventBus.class).get().send(MailEvent.ADDRESS, mailEvent);
        }
    }

    private String toHtmlBody(String body) {
        return body == null
                ? "<p></p>"
                : MarkdownConverter.toHtml(body.replaceAll("<!--.*?-->", ""));
    }
}
