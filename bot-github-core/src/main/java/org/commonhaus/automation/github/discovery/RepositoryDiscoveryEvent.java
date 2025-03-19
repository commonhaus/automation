package org.commonhaus.automation.github.discovery;

import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public record RepositoryDiscoveryEvent(
        DiscoveryAction action,
        GitHub github,
        DynamicGraphQLClient graphQLClient,
        long installationId,
        GHRepository repository,
        boolean bootstrap) {

    public boolean added() {
        return action.added();
    }

    public boolean removed() {
        return action.removed();
    }

    public boolean installation() {
        return action.installation();
    }

    /**
     * Not ideal, but a little priority ordering goes a long way to bringing some
     * sanity across handlers.
     *
     * - CONNECTION_INIT pre/re-caches connections based on the most recent inbound GH Event
     * - CONNECTED updates installation maps with those connections (for scoped contexts)
     *
     * Discovery ordering:
     *
     * - CORE_DISCOVERY find update installations (important for startup)
     * - WATCHER_DISCOVERY is the trigger for file/membership watchers (usually only removal/cleanup)
     * - APP_DISCOVERY set up bot-specific context. Connections should be updated first.
     * - APP_WATCHER is the trigger for bot-specific watchers (usually only removal/cleanup)
     * - APP_EVENT is the trigger for bot-specific event handlers; sometimes best after discovery/watcher
     */
    public static interface RdePriority {
        static final int CONNECTED = 10;
        static final int CORE_DISCOVERY = 15;
        static final int WATCHER_DISCOVERY = 20;
        static final int APP_DISCOVERY = 25;
        static final int APP_EVENT = 35;
    }
}
