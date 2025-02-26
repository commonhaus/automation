package org.commonhaus.automation.github.watchers;

/**
 * Generic interface for a class that watches for changes in resources
 * which can be refreshed.
 */
public interface Watcher {
    /**
     * Refresh all resources that are being watched.
     * <p>
     * Specifically for use with periodic/scheduled tasks
     */
    void refresh();
}
