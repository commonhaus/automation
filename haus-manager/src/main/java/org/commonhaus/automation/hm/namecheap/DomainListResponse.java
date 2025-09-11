package org.commonhaus.automation.hm.namecheap;

import java.util.List;

public record DomainListResponse(
        List<DomainRecord> domains,
        PaginationInfo pagination) {
}