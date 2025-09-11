package org.commonhaus.automation.hm.namecheap;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

@Path("/xml.response")
public interface NamecheapClient {

    default String getDomainList(int page, int pageSize) {
        return getDomainList("namecheap.domains.getlist", page, pageSize);
    }

    @GET
    @Produces(MediaType.APPLICATION_XML)
    String getDomainList(
            @QueryParam("Command") String command,
            @QueryParam("Page") int page,
            @QueryParam("PageSize") int pageSize);

    default String getDomainInfo(String domainName) {
        return getDomainInfo("namecheap.domains.getinfo", domainName);
    }

    @GET
    @Produces(MediaType.APPLICATION_XML)
    String getDomainInfo(
            @QueryParam("Command") String command,
            @QueryParam("DomainName") String domainName);
}
