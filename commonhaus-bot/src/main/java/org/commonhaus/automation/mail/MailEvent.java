package org.commonhaus.automation.mail;

import io.quarkus.mailer.MailTemplate.MailTemplateInstance;

public class MailEvent {
    public final static String ADDRESS = "mail";
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
