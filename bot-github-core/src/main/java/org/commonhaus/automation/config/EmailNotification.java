package org.commonhaus.automation.config;

import java.util.Arrays;

import jakarta.annotation.Nonnull;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record EmailNotification(
        // List of email addresses to send notifications to when errors occur
        String[] errors,
        // List of email addresses to send results of dry run
        String[] dryRun,
        // List of email addresses to send audit/change logs
        String[] audit) {

    static final String[] EMPTY = new String[0];
    public static final EmailNotification UNDEFINED = new EmailNotification(EMPTY, EMPTY, EMPTY);

    @Nonnull
    @Override
    public String[] audit() {
        return audit == null ? EMPTY : audit;
    }

    @Nonnull
    @Override
    public String[] dryRun() {
        return dryRun == null ? EMPTY : dryRun;
    }

    @Nonnull
    @Override
    public String[] errors() {
        return errors == null ? EMPTY : errors;
    }

    @Override
    public String toString() {
        return "EmailNotificationConfig{errors='%s', dryRun='%s', audit='%s'}"
                .formatted(Arrays.toString(errors()), Arrays.toString(dryRun()), Arrays.toString(audit()));
    }


}
