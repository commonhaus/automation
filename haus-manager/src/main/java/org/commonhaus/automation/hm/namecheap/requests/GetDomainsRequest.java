package org.commonhaus.automation.hm.namecheap.requests;

import jakarta.ws.rs.QueryParam;

/**
 * Request bean for namecheap.domains.getList API call.
 * Uses @BeanParam pattern with annotated getters.
 */
public class GetDomainsRequest extends BaseRequest {
    private final int page;
    private final int pageSize;

    public GetDomainsRequest(int page, int pageSize) {
        this.page = page;
        this.pageSize = pageSize;
    }

    @Override
    public String getCommand() {
        return "namecheap.domains.getList";
    }

    @QueryParam("Page")
    public int getPage() {
        return page;
    }

    @QueryParam("PageSize")
    public int getPageSize() {
        return pageSize;
    }
}
