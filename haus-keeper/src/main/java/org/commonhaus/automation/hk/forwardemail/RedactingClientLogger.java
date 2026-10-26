package org.commonhaus.automation.hk.forwardemail;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.client.api.ClientLogger;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;

/**
 * Global REST client logger (applies to every MicroProfile REST client in this app, since
 * Quarkus looks up a single CDI {@link ClientLogger} bean). Behaves like Quarkus'
 * DefaultClientLogger, but masks password-shaped JSON fields in request/response bodies
 * so credentials never reach the log (e.g. ForwardEmail's generate-password request/response).
 */
@ApplicationScoped
public class RedactingClientLogger implements ClientLogger {
    private static final Logger log = Logger.getLogger(RedactingClientLogger.class);
    private static final String MASKED_VALUE = "<hidden>";

    // Matches "password":"...", "new_password":"...", "current_password":"...", etc.
    private static final Pattern PASSWORD_FIELD = Pattern.compile(
            "\"([a-zA-Z_]*password)\"\\s*:\\s*\"(?:[^\"\\\\]|\\\\.)*\"");

    private int bodySize;
    private Set<String> maskedHeaders = Set.of();

    @Override
    public void setBodySize(int bodySize) {
        this.bodySize = bodySize;
    }

    @Override
    public void setMaskedHeaders(Set<String> maskedHeaders) {
        this.maskedHeaders = maskedHeaders;
    }

    @Override
    public void logResponse(HttpClientResponse response, boolean redirect) {
        response.bodyHandler(new Handler<>() {
            @Override
            public void handle(Buffer body) {
                log.debugf("%s: %s %s, Status[%d %s], Headers[%s], Body:\n%s",
                        redirect ? "Redirect" : "Response",
                        response.request().getMethod(), response.request().absoluteURI(), response.statusCode(),
                        response.statusMessage(), formatHeaders(response.headers()), bodyToString(body));
            }
        });
    }

    @Override
    public void logRequest(HttpClientRequest request, Buffer body, boolean omitBody) {
        if (omitBody) {
            log.debugf("Request: %s %s Headers[%s], Body omitted",
                    request.getMethod(), request.absoluteURI(), formatHeaders(request.headers()));
        } else if (body == null || body.length() == 0) {
            log.debugf("Request: %s %s Headers[%s], Empty body",
                    request.getMethod(), request.absoluteURI(), formatHeaders(request.headers()));
        } else {
            log.debugf("Request: %s %s Headers[%s], Body:\n%s",
                    request.getMethod(), request.absoluteURI(), formatHeaders(request.headers()), bodyToString(body));
        }
    }

    private String bodyToString(Buffer body) {
        if (body == null) {
            return "";
        }
        String redacted = redactPasswords(body.toString());
        if (bodySize <= 0) {
            return redacted;
        }
        return redacted.substring(0, Math.min(bodySize, redacted.length()));
    }

    private String redactPasswords(String body) {
        Matcher matcher = PASSWORD_FIELD.matcher(body);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement("\"" + matcher.group(1) + "\":\"" + MASKED_VALUE + "\""));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String formatHeaders(MultiMap headers) {
        if (headers.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder((headers.size() * (6 + 1 + 6)) + (headers.size() - 1));

        for (Map.Entry<String, String> entry : headers) {
            boolean shouldBeMasked = this.maskedHeaders.contains(entry.getKey().toLowerCase(Locale.ROOT));
            String value = shouldBeMasked ? MASKED_VALUE : entry.getValue();
            sb.append(entry.getKey()).append('=').append(value).append(' ');
        }
        sb.setLength(sb.length() - 1);
        return sb.toString();
    }
}
