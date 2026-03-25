package org.commonhaus.automation.hm;

import org.commonhaus.automation.hm.ProjectManager.ProjectConfigState;
import org.commonhaus.automation.hm.config.LatestOrgConfig;
import org.commonhaus.automation.hm.config.ManagerBotConfig;
import org.commonhaus.automation.hm.github.AppContextService;
import org.commonhaus.automation.queue.ScheduledService;

import io.quarkus.logging.Log;

/**
 * Base class for monitoring services that reconcile organizational config,
 * project config, and external systems (NameCheap, GitHub installations, etc.)
 *
 * Provides common patterns for:
 * - Sending project-specific error notifications
 * - Sending organization-level audit summaries
 * - Handling dry-run modes
 */
public abstract class BaseMonitor extends ScheduledService {

    protected abstract AppContextService getCtx();

    protected abstract ManagerBotConfig getMgrBotConfig();

    protected abstract LatestOrgConfig getLatestOrgConfig();

    /**
     * Check if monitoring is enabled at the organization level
     *
     * @return true if monitoring is enabled
     */
    protected abstract boolean isMonitoringEnabled();

    /**
     * Check if in dry-run mode at the organization level
     *
     * @return true if in dry-run mode
     */
    protected abstract boolean isOrgDryRun();

    /**
     * Send error notification to a project with automatic CC to organization
     *
     * @param title Email subject
     * @param message Email body
     * @param state Project configuration state
     * @param dryRun Whether in dry-run mode
     */
    protected void sendProjectErrorNotification(String title, String message,
            ProjectConfigState state, boolean dryRun) {

        if (state == null || state.projectConfig() == null) {
            Log.warnf("[%s] Cannot send notification for project (no config found). title: %s",
                    me(), title);
            return;
        }

        var addresses = state.projectConfig().emailNotifications().errors();

        if (dryRun) {
            Log.infof("[%s] DRY RUN: would send email for project %s to %s. title: %s",
                    me(), state.repoFullName(), String.join(", ", addresses), title);
            getCtx().sendEmail(me(), title, message,
                    getLatestOrgConfig().getConfig().emailNotifications().dryRun());
        } else {
            // Send to project
            getCtx().sendEmail(me(), title, message, addresses);
            // CC to org errors
            getCtx().sendEmail(me(), title, message,
                    getLatestOrgConfig().getConfig().emailNotifications().errors());
        }
    }

    /**
     * Send audit notification to organization
     *
     * @param title Email subject
     * @param message Email body
     * @param dryRun Whether in dry-run mode
     */
    protected void sendOrgAuditNotification(String title, String message, boolean dryRun) {
        var addresses = dryRun
                ? getLatestOrgConfig().getConfig().emailNotifications().dryRun()
                : getLatestOrgConfig().getConfig().emailNotifications().audit();

        getCtx().sendEmail(me(), title, message, addresses);
    }

    /**
     * Send error notification to organization
     *
     * @param title Email subject
     * @param message Email body
     * @param dryRun Whether in dry-run mode
     */
    protected void sendOrgErrorNotification(String title, String message, boolean dryRun) {
        var addresses = dryRun
                ? getLatestOrgConfig().getConfig().emailNotifications().dryRun()
                : getLatestOrgConfig().getConfig().emailNotifications().errors();

        getCtx().sendEmail(me(), title, message, addresses);
    }

    /**
     * Get display name for a project from its repository full name
     *
     * @param repoFullName Repository full name (e.g., "org/project-name")
     * @return Display name (e.g., "name")
     */
    protected String getProjectDisplayName(String repoFullName) {
        if (repoFullName == null || repoFullName.isEmpty()) {
            return "unknown";
        }

        // Extract repository name from full name (after the last '/')
        int lastSlash = repoFullName.lastIndexOf('/');
        String repoName = lastSlash >= 0 ? repoFullName.substring(lastSlash + 1) : repoFullName;

        // Remove "project-" prefix if present
        if (repoName.startsWith("project-")) {
            return repoName.substring("project-".length());
        }

        return repoName;
    }
}
