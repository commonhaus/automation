package org.commonhaus.automation.hk.data;

import static org.assertj.core.api.Assertions.assertThat;

import org.commonhaus.automation.hk.github.DatastoreEvent.UpdateEvent;
import org.junit.jupiter.api.Test;

class DatastoreEventTest {

    @Test
    void noOpAliasUpdateDoesNotAddHistory() {
        CommonhausUser user = CommonhausUser.create("testuser", 123L);
        UpdateEvent event = new UpdateEvent(user, (ctx, target) -> {
        }, "Update user aliases", true, true);

        event.applyChanges(null, user);

        assertThat(user.history).isEmpty();
    }

    @Test
    void changedAliasUpdateAddsOneHistoryEntry() {
        CommonhausUser user = CommonhausUser.create("testuser", 123L);
        UpdateEvent event = new UpdateEvent(user,
                (ctx, target) -> target.services().forwardEmail().addAliases(java.util.Set.of("alias@example.com")),
                "Update user aliases", true, true);

        event.applyChanges(null, user);

        assertThat(user.history).hasSize(1);
        assertThat(user.history.iterator().next()).endsWith("Update user aliases");
    }
}
