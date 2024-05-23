package org.commonhaus.automation.admin.forwardemail;

import java.util.Base64;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@Path("/v1")
@RegisterRestClient(configKey = "forward-email-api")
@ClientHeaderParam(name = "Authorization", value = "{lookupAuth}")
public interface ForwardEmailClient {
    AtomicReference<String> token = new AtomicReference<>();

    @Path("/domains")
    @GET
    Set<Domain> getDomains();

    // @Path("/domains")
    // @POST
    // Domain createDomain(Domain domain);

    // @Path("/domains/{fqdn}/verify-records")
    // @POST
    // void verifyRecords(@PathParam("fqdn") String fqdn);

    @Path("/domains/{fqdn}/aliases")
    @GET
    Set<Alias> getAliases(@PathParam("fqdn") String fqdn);

    @Path("/domains/{fqdn}/aliases")
    @POST
    void createAliases(@PathParam("fqdn") String fqdn, Alias alias);

    default String lookupAuth() {
        String value = token.get();
        if (token.get() == null) {
            String apiKey = ConfigProvider.getConfig().getValue("forward.email.api.key", String.class);
            value = "Basic " + Base64.getEncoder().encodeToString((apiKey + ":").getBytes());
            token.set(value);
        }
        return value;
    }
}
