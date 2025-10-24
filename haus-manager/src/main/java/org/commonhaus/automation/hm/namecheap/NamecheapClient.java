package org.commonhaus.automation.hm.namecheap;

import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import org.commonhaus.automation.hm.namecheap.models.DomainContacts;
import org.commonhaus.automation.hm.namecheap.requests.CreateDomainRequest;
import org.commonhaus.automation.hm.namecheap.requests.GetContactsRequest;
import org.commonhaus.automation.hm.namecheap.requests.GetDomainsRequest;
import org.commonhaus.automation.hm.namecheap.requests.SetContactsRequest;

/**
 * Namecheap API REST client.
 * Uses @BeanParam pattern to keep method signatures clean despite the API's
 * requirement for many query parameters.
 *
 * Note: The Namecheap API uses GET for all operations (even mutations),
 * and returns XML responses.
 */
@Path("/xml.response")
public interface NamecheapClient {

    // Domain list operations

    @GET
    @Produces(MediaType.APPLICATION_XML)
    String getDomainList(@BeanParam GetDomainsRequest request);

    default String getDomainList(int page, int pageSize) {
        return getDomainList(new GetDomainsRequest(page, pageSize));
    }

    // Domain info operations

    default String getDomainInfo(String domainName) {
        return getDomainInfo("namecheap.domains.getinfo", domainName);
    }

    @GET
    @Produces(MediaType.APPLICATION_XML)
    String getDomainInfo(
            @QueryParam("Command") String command,
            @QueryParam("DomainName") String domainName);

    // Contact operations

    @GET
    @Produces(MediaType.APPLICATION_XML)
    String getContacts(@BeanParam GetContactsRequest request);

    default String getContacts(String domainName) {
        return getContacts(new GetContactsRequest(domainName));
    }

    @GET
    @Produces(MediaType.APPLICATION_XML)
    String setContacts(@BeanParam SetContactsRequest request);

    default String setContacts(String domainName, DomainContacts contacts) {
        return setContacts(new SetContactsRequest(domainName, contacts));
    }

    // Domain creation (primarily for testing in sandbox)

    @GET
    @Produces(MediaType.APPLICATION_XML)
    String createDomain(@BeanParam CreateDomainRequest request);
}
