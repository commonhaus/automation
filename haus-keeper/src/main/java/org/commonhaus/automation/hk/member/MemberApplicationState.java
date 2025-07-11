package org.commonhaus.automation.hk.member;

import org.commonhaus.automation.ContextService;
import org.commonhaus.automation.github.context.DataCommonItem;
import org.commonhaus.automation.hk.data.ApplicationIssue;
import org.commonhaus.automation.hk.data.CommonhausUser;
import org.commonhaus.automation.mail.LogMailer;

import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * Coordinate state for member application processing using
 * old-fashioned synchronization.
 *
 * <ul>
 * <li>{@link #beginOperation()} and {@link #releaseOperation()} are used to lock the state.
 * <li>{@link #refreshApplication(DataCommonItem)} and {@link #refreshData(MemberApplication)}
 * update the application state.
 * <li>{@link #isValid()} checks if the application data is valid
 * <li>{@link #deepCopy(MemberApplication)} creates a disconnected copy of the essential data.
 * <li>{@link #userUpdateRequired(CommonhausUser)} tests if the user record needs to be updated.
 * </ul>
 */
public class MemberApplicationState {
    private final String login;
    private final long id;

    private MemberApplication appData;
    private DataCommonItem issue; // Store the full issue data
    private boolean inProgress = false;

    public MemberApplicationState(String login, long id) {
        this.login = login;
        this.id = id;
    }

    /**
     * Begin an atomic operation on this application.
     * Returns true if the lock was acquired, false otherwise.
     */
    public synchronized boolean beginOperation() {
        if (inProgress) {
            return false;
        }
        return inProgress = true;
    }

    public synchronized String getIssueId() {
        if (appData != null && appData.isValid()) {
            return appData.appIssue.nodeId();
        }
        if (issue != null) {
            return issue.id;
        }
        return null;
    }

    /**
     * Get the raw issue item data, which might be null if not yet fetched.
     */
    public synchronized DataCommonItem getIssue() {
        return issue;
    }

    /**
     * Refresh application state from a discovered/fetched item.
     */
    public synchronized void refreshApplication(DataCommonItem item) {
        if (item != null) {
            MemberApplication data = new MemberApplication(login, item);
            if (data.isValid()) {
                // Issue found and owner matches
                this.issue = item; // immutable
                this.appData = data;
                return;
            }
        }
        this.issue = null;
        this.appData = null;
    }

    /**
     * Refresh application state from a discovered/fetched item.
     */
    public synchronized void refreshData(MemberApplication data) {
        this.appData = deepCopy(data);
    }

    /**
     * Release the lock without updating application state.
     * Use when an operation fails or when only reading.
     */
    public synchronized void releaseOperation() {
        inProgress = false;
    }

    /**
     * Get the current application data, or null if not initialized.
     */
    public MemberApplication getApplication() {
        return deepCopy(appData);
    }

    /**
     * Check if the application data is valid and matches the current user.
     */
    public synchronized boolean isValid() {
        return appData != null &&
                appData.isValid() &&
                appData.ownerEquals(login);
    }

    /**
     * Get the current application issue reference, or null if not initialized.
     */
    public synchronized ApplicationIssue getAppIssue(CommonhausUser user) {
        return appData == null ? user.application() : appData.appIssue; // immutable
    }

    /**
     * Create a deep copy of the member application to avoid concurrent modifications.
     * static/stateless method for easy access. Should be rare event.
     */
    static MemberApplication deepCopy(MemberApplication x) {
        if (x == null) {
            return null;
        }
        // create a disconnected copy of the essential data.
        try {
            String json = ContextService.yamlMapper.writeValueAsString(x);
            MemberApplication copy = ContextService.yamlMapper.readValue(json, MemberApplication.class);
            copy.appIssue = x.appIssue; // immutable
            copy.title = x.title; // immutable
            return copy;
        } catch (JsonProcessingException e) {
            LogMailer.instance().logAndSendEmail(
                    "MemberApplication.deepCopy",
                    "Unable to copy MemberApplication", e);
        }
        return x;
    }

    public boolean userUpdateRequired(CommonhausUser user) {
        // Indicate whether or not the user record needs to be updated
        // 1) the application data is not valid (issue not found or owner mismatch)
        // 2) the user status should move to pending (application exists and is valid)
        if (appData == null && user.application() == null) {
            return false;
        }
        return !isValid() || user.status().missedUpdateToPending();
    }
}
