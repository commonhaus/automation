package org.commonhaus.automation.mail;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.logging.Log;
import io.quarkus.mailer.MailTemplate.MailTemplateInstance;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.mutiny.core.eventbus.Message;

@ApplicationScoped
class MailConsumer {
    static Optional<String> replyAddress = null;

    /**
     * Add the reply-to address to the mail template instance.
     *
     * @param mailTemplateInstance the mail template instance
     */
    static void addReplyTo(MailTemplateInstance mailTemplateInstance) {
        if (replyAddress == null) {
            // This is not thread-sensitive: it's okay if multiple threads set the value.
            replyAddress = ConfigProvider.getConfig().getOptionalValue("automation.error-email-address", String.class);
        }
        if (replyAddress.isPresent()) {
            mailTemplateInstance.replyTo(replyAddress.get());
        }
    }

    @ConsumeEvent(MailEvent.ADDRESS)
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
                        failure -> Log.errorf(failure, "[%s] EmailAction.apply: Failed to send email to %s",
                                mailEvent.logId,
                                List.of(mailEvent.addresses),
                                mailEvent.subject));
    }
}
