package org.commonhaus.automation.mail;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;

import org.commonhaus.automation.github.context.PackagedException;
import org.commonhaus.automation.markdown.MarkdownConverter;
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
    private static LogMailer instance;

    public static LogMailer instance() {
        if (instance == null) {
            instance = Arc.container().instance(LogMailer.class).get();
        }
        return instance;
    }

    static final String[] EMPTY = new String[0];

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

    @Nonnull
    public String[] botErrorEmailAddress() {
        return errorAddress == null ? EMPTY : errorAddress;
    }

    /**
     * Queue an email (EventBus, fire-and-forget).
     *
     * @param logId The log identifier
     * @param title The email title
     * @param body The email body
     * @param addresses The email addresses
     * @see MailEvent
     * @see MailConsumer
     */
    public void sendEmail(@Nonnull String logId, @Nonnull String title,
            @Nonnull String body, @Nonnull String[] addresses) {
        String htmlBody = MarkdownConverter.toHtml(body.toString());
        MailEvent event = new MailEvent(logId, Templates.basicEmail(title, body, htmlBody), title, addresses);
        if (event.hasAddresses()) {
            bus.send(MailEvent.ADDRESS, event);
        }
    }

    /**
     * Queue an email (EventBus, fire-and-forget).
     *
     * @param logId The log identifier
     * @param title The email title
     * @param body The email body
     * @param addresses The email addresses
     * @see MailEvent
     * @see MailConsumer
     */
    public void sendEmail(@Nonnull String logId, @Nonnull String title,
            MailTemplateInstance mailTemplate, @Nonnull String[] addresses) {
        MailEvent event = new MailEvent(logId, mailTemplate, title, addresses);
        if (event.hasAddresses()) {
            bus.send(MailEvent.ADDRESS, event);
        }
    }

    /**
     * Log an error and queue an email (EventBus, fire-and-forget).
     *
     * @see #logAndSendEmail(String, String, String, Throwable, String[])
     */
    public void logAndSendEmail(String logId, String title, Throwable t) {
        this.logAndSendEmail(logId, title, "", t, botErrorEmailAddress());
    }

    /**
     * Log an error and queue an email (EventBus, fire-and-forget).
     *
     * @see #logAndSendEmail(String, String, String, Throwable, String[])
     */
    public void logAndSendEmail(String logId, String title, Throwable t, String[] addresses) {
        this.logAndSendEmail(logId, title, "", t, addresses);
    }

    /**
     * Log an error and queue an email (EventBus, fire-and-forget).
     *
     * @param logId
     * @param title
     * @param body
     * @param t
     * @param addresses
     * @see #createErrorMailEvent(String, String, String, Throwable, String[])
     * @see MailEvent
     * @see MailConsumer
     */
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

    /**
     * Create a MailEvent for an error.
     *
     * @param logId
     * @param title
     * @param body
     * @param e
     * @param addresses
     * @return
     */
    MailEvent createErrorMailEvent(String logId, String title, String body, Throwable e, @Nonnull String[] addresses) {
        // If configured to do so, email the error_email_address
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        if (body != null) {
            pw.println(body);
            pw.println();
        }
        if (e instanceof PackagedException) {
            PackagedException pe = (PackagedException) e;
            pw.println(pe.details());
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
