package org.commonhaus.automation.hm.config;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record EmailNotification(
        // List of email addresses to send notifications to when errors occur
        String[] errors,
        // List of email addresses to send results of dry run
        String[] dryRun) {

    @Override
    public String toString() {
        return "EmailNotificationConfig{errors=%s, dryRun='%s'}"
                .formatted(errors, dryRun);
    }
}
