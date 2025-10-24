package org.commonhaus.automation.hm.namecheap.requests;

import jakarta.ws.rs.QueryParam;

/**
 * Request bean for namecheap.domains.getContacts API call.
 * Uses @BeanParam pattern with annotated getters.
 */
public class GetContactsRequest extends BaseRequest {
    private final String domainName;

    public GetContactsRequest(String domainName) {
        this.domainName = domainName;
    }

    @Override
    public String getCommand() {
        return "namecheap.domains.getContacts";
    }

    @QueryParam("DomainName")
    public String getDomainName() {
        return domainName;
    }
}
