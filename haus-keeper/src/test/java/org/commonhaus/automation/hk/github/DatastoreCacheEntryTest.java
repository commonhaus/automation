package org.commonhaus.automation.hk.github;

import static org.assertj.core.api.Assertions.assertThat;

import org.commonhaus.automation.ContextService;
import org.commonhaus.automation.hk.data.CommonhausUser;
import org.commonhaus.automation.hk.github.DatastoreEvent.UpdateEvent;
import org.junit.jupiter.api.Test;

class DatastoreCacheEntryTest {

    @Test
    void noOpUpdateIsNotQueuedForPersistence() {
        DatastoreCacheEntry entry = new DatastoreCacheEntry("testuser-123");
        entry.refreshUserData(CommonhausUser.create("testuser", 123L));

        entry.applyUpdate(null, new UpdateEvent(entry.getUserData(), (ctx, user) -> {
        }, "Update user aliases", true, true));

        assertThat(entry.hasPendingUpdates()).isFalse();
    }

    @Test
    void changedUpdateIsQueuedForPersistence() {
        DatastoreCacheEntry entry = new DatastoreCacheEntry("testuser-123");
        entry.refreshUserData(CommonhausUser.create("testuser", 123L));

        entry.applyUpdate(null, new UpdateEvent(entry.getUserData(),
                (ctx, user) -> user.services().forwardEmail().addAliases(java.util.Set.of("alias@example.com")),
                "Update user aliases", true, true));

        assertThat(entry.hasPendingUpdates()).isTrue();
    }

    @Test
    void retryAgainstAlreadyUpdatedUserDoesNotAddHistory() throws Exception {
        DatastoreCacheEntry entry = new DatastoreCacheEntry("testuser-123");
        entry.refreshUserData(CommonhausUser.create("testuser", 123L));
        UpdateEvent event = new UpdateEvent(entry.getUserData(),
                (ctx, user) -> user.services().forwardEmail().addAliases(java.util.Set.of("alias@example.com")),
                "Update user aliases", true, true);

        entry.applyUpdate(null, event);
        entry.beginUpdate();

        CommonhausUser latest = CommonhausUser.create("testuser", 123L);
        latest.services().forwardEmail().addAliases(java.util.Set.of("alias@example.com"));
        entry.handleConflict(null, latest);

        assertThat(entry.hasPendingUpdates()).isFalse();
        assertThat(ContextService.yamlMapper.writeValueAsString(entry.getUserData()))
                .doesNotContain("Update user aliases");
    }
}
