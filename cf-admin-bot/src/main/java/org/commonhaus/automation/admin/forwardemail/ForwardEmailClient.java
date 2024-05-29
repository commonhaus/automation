package org.commonhaus.automation.admin.forwardemail;

import java.util.Base64;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;

import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@Path("/v1")
@RegisterRestClient(configKey = "forward-email-api")
@ClientHeaderParam(name = "Authorization", value = "{lookupAuth}")
public interface ForwardEmailClient {
    AtomicReference<String> token = new AtomicReference<>();

    @GET
    @Path("/domains")
    Set<Domain> getDomains();

    @GET
    @Path("/domains/{fqdn}/aliases")
    Set<Alias> getAliases(@PathParam("fqdn") String fqdn);

    @GET
    @Path("/domains/{fqdn}/aliases/{id}")
    Alias getAlias(@PathParam("fqdn") String fqdn, @PathParam("id") String id);

    @GET
    @Path("/domains/{fqdn}/aliases")
    Set<Alias> findAliasByName(@PathParam("fqdn") String fqdn, @QueryParam("name") String name);

    @POST
    @Path("/domains/{fqdn}/aliases")
    void createAlias(@PathParam("fqdn") String fqdn, Alias alias);

    @PUT
    @Path("/domains/{fqdn}/aliases/{id}")
    void updateAlias(@PathParam("fqdn") String fqdn, @PathParam("id") String id, Alias alias);

    // TODO: Not available yet... SOON
    // @POST
    // @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    // @Path("/domains/{fqdn}/aliases/{id}/generate-password")
    // void generatePassword(@PathParam("fqdn") String fqdn, @PathParam("id") String id, @FormParam("name") String emailAddress);

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
