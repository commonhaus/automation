package org.commonhaus.automation.mail;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.logging.Log;
import io.quarkus.mailer.MailTemplate.MailTemplateInstance;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.mutiny.core.eventbus.Message;

@ApplicationScoped
public class MailConsumer {

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

    public static class MailEvent {
        final String logId;
        final MailTemplateInstance mailTemplateInstance;
        final String subject;
        final String[] addresses;

        public MailEvent(String logId, MailTemplateInstance mailTemplateInstance, String subject, String[] addresses) {
            this.logId = logId;
            this.mailTemplateInstance = mailTemplateInstance;
            this.subject = subject;
            this.addresses = addresses;
        }
    }

    @ConsumeEvent("mail")
    public void consume(Message<MailEvent> msg) {
        MailEvent mailEvent = msg.body();

        addReplyTo(mailEvent.mailTemplateInstance);
        mailEvent.mailTemplateInstance
                .to(mailEvent.addresses)
                .subject(mailEvent.subject)
                .send()
                .subscribe().with(
                        success -> Log.infof("%s EmailAction.apply: Email sent to %s; %s",
                                mailEvent.logId,
                                List.of(mailEvent.addresses),
                                mailEvent.subject),
                        failure -> Log.errorf(failure, "%s EmailAction.apply: Failed to send email to %s",
                                mailEvent.logId,
                                List.of(mailEvent.addresses),
                                mailEvent.subject));
    }
}
