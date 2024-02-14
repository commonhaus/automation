package org.commonhaus.automation.github.actions;

import java.net.URL;
import java.util.List;

import org.commonhaus.automation.github.EventData;
import org.commonhaus.automation.github.QueryHelper.QueryContext;
import org.commonhaus.automation.github.model.DataDiscussion;
import org.commonhaus.automation.github.model.EventPayload;
import org.commonhaus.automation.github.model.EventPayload.DiscussionPayload;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHPullRequest;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import io.quarkus.arc.Arc;
import io.quarkus.logging.Log;
import io.quarkus.mailer.MailTemplate.MailTemplateInstance;
import io.quarkus.qute.CheckedTemplate;

@JsonDeserialize(as = EmailAction.class)
public class EmailAction extends Action {
    static final Parser parser = Parser.builder().build();
    static final HtmlRenderer renderer = HtmlRenderer.builder().build();

    static boolean initialized = false;
    static String replyToAddress = null;

    static void addReplyTo(MailTemplateInstance mailTemplateInstance) {
        String replyTo = replyToAddress;
        if (replyTo == null && !initialized) {
            replyToAddress = replyTo = ConfigProvider.getConfig().getValue("automation.replyTo", String.class);
            initialized = true;
        }
        if (replyTo != null) {
            mailTemplateInstance.replyTo(replyTo);
        }
    }

    @CheckedTemplate
    static class Templates {
        public static native MailTemplateInstance pullRequestEvent(String title, String htmlBody,
                GHPullRequest pullRequest);

        public static native MailTemplateInstance discussionEvent(String title, String htmlBody,
                DataDiscussion discussion, String repoSlug, URL repoUrl);
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
    public void apply(QueryContext queryContext) {
        EventData eventData = queryContext.getEventData();
        // Fire and forget: mail construction and sending happens in a separate thread
        Arc.container().instance(ManagedExecutor.class).get().submit(() -> {
            Log.debugf("EmailAction.apply: Preparing email to %s for %s.%s", List.of(addresses), eventData.getEventType(),
                    eventData.getAction());

            final String subject;
            MailTemplateInstance mailTemplateInstance;
            try {
                mailTemplateInstance = switch (eventData.getEventType()) {
                    case pull_request -> {
                        GHEventPayload.PullRequest payload = eventData.getGHEventPayload();

                        subject = "PR #" + payload.getNumber() + " " + payload.getPullRequest().getTitle();
                        yield Templates.pullRequestEvent(subject,
                                toHtmlBody(payload.getPullRequest().getBody()),
                                payload.getPullRequest());
                    }
                    case discussion -> {
                        EventPayload.DiscussionPayload payload = (DiscussionPayload) eventData.getEventPayload();
                        DataDiscussion discussion = payload.discussion;

                        subject = "#" + discussion.number + " " + discussion.title + " (" + discussion.category.name + ")";
                        yield Templates.discussionEvent(subject,
                                toHtmlBody(discussion.body),
                                discussion,
                                eventData.getRepository().getFullName(),
                                eventData.getRepository().getUrl());
                    }
                    default -> {
                        Log.warnf("EmailAction.apply: unsupported event type %s", eventData.getEventType());
                        subject = null;
                        yield null;
                    }
                };
            } catch (Exception e) {
                mailTemplateInstance = null;
                Log.errorf(e, "EmailAction.apply: Failed to prepare email to %s", List.of(addresses));
                return;
            }

            if (mailTemplateInstance != null) {
                Log.debugf("EmailAction.apply: Sending email to %s; %s", List.of(addresses), subject);
                addReplyTo(mailTemplateInstance);
                mailTemplateInstance
                        .to(addresses)
                        .subject(subject)
                        .send()
                        .subscribe().with(
                                success -> Log.infof("EmailAction.apply: Email sent to %s; %s", List.of(addresses), subject),
                                failure -> Log.errorf(failure, "EmailAction.apply: Failed to send email to %s",
                                        List.of(addresses),
                                        subject));
            }
        });
    }

    private String toHtmlBody(String body) {
        if (body == null) {
            return "<p></p>";
        }
        try {
            Node document = parser.parse(body);
            return renderer.render(document);
        } catch (Exception e) {
            Log.errorf(e, "EmailAction.toHtmlBody: Failed to render body as HTML");
            return body;
        }
    }
}
