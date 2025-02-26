package org.commonhaus.automation.mail;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.arc.Arc;
import io.quarkus.logging.Log;
import io.quarkus.mailer.MailTemplate.MailTemplateInstance;
import io.quarkus.qute.CheckedTemplate;
import io.vertx.mutiny.core.eventbus.EventBus;

/**
 * A simple mailer for sending email.
 * <p>
 * Extended by {@link org.commonhaus.automation.github.context.BaseContextService}
 * to provide basic email sending capabilities for logging errors.
 * <p>
 * Uses programmatic lookups for BotConfig and EventBus to allow it to be
 * used as a fallback in the event that the ContextSerivce is not available.
 */
@ApplicationScoped
public class LogMailer {
    @CheckedTemplate
    static class Templates {
        public static native MailTemplateInstance basicEmail(String title, String body, String htmlBody);
    }

    private final EventBus bus;
    private final String[] errorAddress;

    public LogMailer() {
        this.bus = Arc.container().instance(EventBus.class).get();
        Optional<String> addresses = ConfigProvider.getConfig().getOptionalValue("automation.error-email-address",
                String.class);
        this.errorAddress = addresses.isPresent()
                ? new String[] { addresses.get() }
                : null;
    }

    public String[] botErrorEmailAddress() {
        return errorAddress;
    }

    public void sendEmail(String logId, String title, String body, String htmlBody, String[] addresses) {
        MailEvent event = new MailEvent(logId, Templates.basicEmail(title, body, htmlBody), title, addresses);
        if (event.hasAddresses()) {
            bus.send(MailEvent.ADDRESS, event);
        }
    }

    public void logAndSendEmail(String logId, String title, Throwable t, String[] addresses) {
        if (t == null) {
            Log.errorf("[%s] %s", logId, title);
        } else {
            Log.errorf(t, "[%s] %s: %s", logId, title, "" + t);
            if (Log.isDebugEnabled()) {
                t.printStackTrace();
            }
        }
        MailEvent event = createErrorMailEvent(logId, title, "", t, addresses);
        if (event.hasAddresses()) {
            bus.send(MailEvent.ADDRESS, event);
        }
    }

    public void logAndSendEmail(String logId, String title, String body, Throwable t, String[] addresses) {
        if (t == null) {
            Log.errorf(t, "[%s] %s: %s", logId, title, body);
        } else {
            Log.errorf(t, "[%s] %s: %s; %s", logId, title, "" + t, body);
            if (Log.isDebugEnabled()) {
                t.printStackTrace();
            }
        }
        MailEvent event = createErrorMailEvent(logId, title, body, t, addresses);
        if (event.hasAddresses()) {
            bus.send(MailEvent.ADDRESS, event);
        }
    }

    MailEvent createErrorMailEvent(String logId, String title, String body, Throwable e, String[] addresses) {
        // If configured to do so, email the error_email_address
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        if (body != null) {
            pw.println(body);
            pw.println();
        }
        if (e != null) {
            e.printStackTrace(pw);
            pw.println();
        }

        String messageBody = sw.toString();
        String htmlBody = messageBody.replace("\n", "<br/>\n");

        MailTemplateInstance mail = Templates.basicEmail(title, messageBody, htmlBody);
        return new MailEvent(logId, mail, title,
                addresses == null ? botErrorEmailAddress() : addresses);
    }
}
