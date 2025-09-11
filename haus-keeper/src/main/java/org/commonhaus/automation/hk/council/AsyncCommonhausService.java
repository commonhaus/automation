package org.commonhaus.automation.hk.council;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.hk.api.MemberSession;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import io.quarkus.logging.Log;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.Blocking;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.core.eventbus.Message;

/**
 * Consumer for AsyncServiceEvent events.
 * Handles fire-and-forget calls to other services.
 */
@ApplicationScoped
public class AsyncCommonhausService {

    @RestClient
    HausManagerClient hausManagerClient;

    @RestClient
    HausRulesClient hausRulesClient;

    @Inject
    EventBus eventBus;

    public void triggerOrgUpdate(MemberSession session) {
        Log.debugf("[%s] Trigger org update", session.login());
        if (session.roles().contains("egc")) {
            AsyncServiceEvent event = new AsyncServiceEvent(
                    session.login(),
                    "HausManager.triggerOrgUpdate",
                    () -> {
                        hausManagerClient.triggerOrgUpdate();
                        return null;
                    });
            eventBus.send(AsyncServiceEvent.ADDRESS, event);
        }
    }

    public void triggerProjectUpdate(MemberSession session) {
        Log.debugf("[%s] Trigger project update", session.login());
        if (session.roles().contains("cfc")) {
            AsyncServiceEvent event = new AsyncServiceEvent(
                    session.login(),
                    "HausManager.triggerProjectUpdate",
                    () -> {
                        hausManagerClient.triggerProjectUpdate();
                        return null;
                    });
            eventBus.send(AsyncServiceEvent.ADDRESS, event);
        }
    }

    public void triggerSponsorUpdate(MemberSession session) {
        Log.debugf("[%s] Trigger sponsor update", session.login());
        if (session.roles().contains("cfc")) {
            AsyncServiceEvent event = new AsyncServiceEvent(
                    session.login(),
                    "HausManager.triggerSponsorUpdate",
                    () -> {
                        hausManagerClient.triggerSponsorUpdate();
                        return null;
                    });
            eventBus.send(AsyncServiceEvent.ADDRESS, event);
        }
    }

    public void triggerVoteCount(MemberSession session) {
        Log.debugf("[%s] Trigger vote counting", session.login());
        if (session.roles().contains("cfc")) {
            AsyncServiceEvent event = new AsyncServiceEvent(
                    session.login(),
                    "HausRules.triggerVoteCount",
                    () -> {
                        hausRulesClient.triggerVoteCount();
                        return null;
                    });
            eventBus.send(AsyncServiceEvent.ADDRESS, event);
        }
    }

    /**
     * Consume and process AsyncServiceEvent events.
     * Executes the service call and logs the result.
     *
     * @param msg Message containing the AsyncServiceEvent
     */
    @ConsumeEvent(AsyncServiceEvent.ADDRESS)
    @Blocking
    protected void consume(Message<AsyncServiceEvent> msg) {
        AsyncServiceEvent event = msg.body();
        try {
            // Execute the service call
            event.serviceCall.get();
            Log.infof("[%s] AsyncServiceConsumer: Successfully called %s",
                    event.logId, event.serviceName);
        } catch (Exception e) {
            // Log any errors but don't propagate them
            Log.errorf(e, "[%s] AsyncServiceConsumer: Failed to call %s",
                    event.logId, event.serviceName);
        }
    }

    @Singleton
    @RegisterRestClient(configKey = "haus-manager")
    public interface HausManagerClient {
        @GET
        @Path("/projects")
        Response triggerProjectUpdate();

        @GET
        @Path("/org")
        Response triggerOrgUpdate();

        @GET
        @Path("/sponsors")
        Response triggerSponsorUpdate();
    }

    @Singleton
    @RegisterRestClient(configKey = "haus-rules")
    public interface HausRulesClient {
        @GET
        @Path("/votes")
        Response triggerVoteCount();
    }
}
