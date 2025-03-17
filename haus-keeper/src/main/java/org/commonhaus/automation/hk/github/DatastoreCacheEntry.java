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

        // Apply changes to our cached copy
        event.applyChanges(ctx, userData);

        // Add to pending updates
        pendingUpdates.add(event);

        // Return a copy of the updated user data
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

        // Accumulate all pending updates into a single in-flight list.
        inFlightUpdates.addAll(pendingUpdates);
        pendingUpdates.clear();

        // Create a new snapshot of the user data.
        return inFlightSnapshot = CommonhausDatastore.deepCopy(userData);
    }

    public synchronized void finishUpdate(CommonhausUser freshUser) {
        inFlightUpdates.clear();
        inFlightSnapshot = null;
        refreshUserData(freshUser);
    }

    public synchronized CommonhausUser handleConflict(AppContextService ctx, CommonhausUser gitHubVersion) {
        // 1. Store the GitHub version as our new base
        this.userData = CommonhausDatastore.deepCopy(gitHubVersion);

        // 2. Collect ALL pending updates (both in-flight and newly added)
        List<UpdateEvent> allUpdates = new ArrayList<>(inFlightUpdates);
        allUpdates.addAll(pendingUpdates);

        // 3. Clear the pending updates since we're reapplying everything
        pendingUpdates.clear();

        // 4. Filter out updates that can't be retried
        allUpdates.removeIf(x -> !x.retry());

        // 5. Reapply all updates to the new base version
        for (UpdateEvent event : allUpdates) {
            event.applyChanges(ctx, userData);
            // Re-add to pending updates (they still need to be persisted)
            pendingUpdates.add(event);
        }

        // 6. Clear the active update so we can start fresh
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
