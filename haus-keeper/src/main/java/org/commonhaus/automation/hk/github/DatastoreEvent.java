package org.commonhaus.automation.hk.github;

import java.util.function.BiConsumer;

import org.commonhaus.automation.hk.data.CommonhausUser;

/**
 * Events that can be applied to the datastore
 */
public interface DatastoreEvent {

    long id();

    String login();

    boolean create();

    /**
     * Common interface for events that commit changes to the datastore
     */
    interface DatastoreCommitEvent extends DatastoreEvent {
        String message();
    }

    /**
     * Query Commonhaus user data
     *
     * @param login Commonhaus user login
     * @param id Commonhaus user id
     * @param refresh Whether to refresh the data from the datastore
     * @param create Whether to create the user if it does not exist
     */
    public record QueryEvent(String login, long id, boolean refresh,
            boolean create) implements DatastoreEvent {
        public QueryEvent(UpdateEvent event) {
            this(event.login(), event.id(), true, event.create());
        }
    }

    /**
     * Update Commonhaus user data
     *
     * @param user Commonhaus user object
     * @param updateUser Function to apply changes to the user. This function will not have access to the MemberSession.
     * @param message Commit message
     * @param history Whether to add the message to the user's history
     * @param retry Whether to retry the update if there is a conflict
     */
    public record UpdateEvent(
            CommonhausUser user,
            BiConsumer<AppContextService, CommonhausUser> updateUser,
            String message,
            boolean history,
            boolean retry) implements DatastoreCommitEvent {

        @Override
        public long id() {
            return user.id();
        }

        @Override
        public String login() {
            return user.login();
        }

        @Override
        public boolean create() {
            return true;
        }

        public void applyChanges(AppContextService ctx, CommonhausUser user) {
            updateUser.accept(ctx, user);
            // Add to history if requested
            if (history) {
                user.addHistory(message);
            }
        }

        static UpdateEvent retryEvent(UpdateEvent initial, CommonhausUser revisedUser) {
            return new UpdateEvent(revisedUser, initial.updateUser(), initial.message(), initial.history(), false);
        }
    }
}
