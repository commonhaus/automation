package org.commonhaus.automation.hk.github;

import java.util.ArrayList;
import java.util.List;

import org.commonhaus.automation.hk.data.CommonhausUser;
import org.commonhaus.automation.hk.github.DatastoreEvent.UpdateEvent;

public class DatastoreCacheEntry {
    private final String userKey;

    private final List<UpdateEvent> pendingUpdates = new ArrayList<>();
    private CommonhausUser userData;

    private CommonhausUser inFlightSnapshot;
    private final List<UpdateEvent> inFlightUpdates = new ArrayList<>();

    public DatastoreCacheEntry(String userKey) {
        this.userKey = userKey;
    }

    public String userKey() {
        return userKey;
    }

    /**
     * Update the cached user data with a fresh copy from GitHub
     */
    public synchronized void refreshUserData(CommonhausUser freshUser) {
        this.userData = CommonhausDatastore.deepCopy(freshUser);
    }

    /**
     * Apply an update to the cached user data and queue it for persistence
     */
    public synchronized CommonhausUser applyUpdate(AppContextService ctx, UpdateEvent event) {
        if (userData == null) {
            return null;
        }

        event.applyChanges(ctx, userData);
        pendingUpdates.add(event);
        return CommonhausDatastore.deepCopy(userData);
    }

    /**
     * Begin processing the next pending update
     */
    public synchronized CommonhausUser beginUpdate() {
        if (inFlightSnapshot != null) {
            // return active/unfinished update.
            return inFlightSnapshot;
        } else if (pendingUpdates.isEmpty()) {
            return null;
        }

        inFlightUpdates.addAll(pendingUpdates);
        pendingUpdates.clear();
        return inFlightSnapshot = CommonhausDatastore.deepCopy(userData);
    }

    public synchronized void finishUpdate(CommonhausUser freshUser) {
        inFlightUpdates.clear();
        inFlightSnapshot = null;
        refreshUserData(freshUser);
    }

    public synchronized CommonhausUser handleConflict(AppContextService ctx, CommonhausUser gitHubVersion) {
        this.userData = CommonhausDatastore.deepCopy(gitHubVersion);

        // Reapply in-flight + pending updates on top of the new base, dropping any that aren't retriable
        List<UpdateEvent> allUpdates = new ArrayList<>(inFlightUpdates);
        allUpdates.addAll(pendingUpdates);
        pendingUpdates.clear();
        allUpdates.removeIf(x -> !x.retry());

        for (UpdateEvent event : allUpdates) {
            event.applyChanges(ctx, userData);
            pendingUpdates.add(event);
        }
        inFlightSnapshot = null;

        return getUserData();
    }

    /**
     * Get a copy of the current user data
     */
    public synchronized CommonhausUser getUserData() {
        return userData == null
                ? null
                : CommonhausDatastore.deepCopy(userData);
    }

    /**
     * @return true if userData is present
     */
    public synchronized boolean hasUserData() {
        return userData != null;
    }

    /**
     * @return true if userData is present and there are pending updates
     */
    public synchronized boolean hasPendingUpdates() {
        return userData != null && !pendingUpdates.isEmpty();
    }

    /**
     * @return true if userData is missing or there are no pending updates
     */
    public synchronized boolean isEmpty() {
        return userData == null || pendingUpdates.isEmpty();
    }

    public Object commitMessage() {
        return inFlightUpdates.size() == 1
                ? inFlightUpdates.get(0).message()
                : inFlightUpdates.size() + " updates";
    }
}
