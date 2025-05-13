package org.commonhaus.automation.hk.council;

import java.util.function.Supplier;

/**
 * Event class for asynchronous service calls.
 * Used for fire-and-forget calls to other services.
 */
public class AsyncServiceEvent {
    public final static String ADDRESS = "async-service";

    final String logId;
    final String serviceName;
    final Supplier<Void> serviceCall;

    /**
     * Create a new AsyncServiceEvent.
     *
     * @param logId The log identifier, typically the user's login
     * @param serviceName The name of the service being called, for logging
     * @param serviceCall The supplier that makes the actual service call
     */
    public AsyncServiceEvent(String logId, String serviceName, Supplier<Void> serviceCall) {
        this.logId = logId;
        this.serviceName = serviceName;
        this.serviceCall = serviceCall;
    }
}
