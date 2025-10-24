package org.commonhaus.automation.hm.namecheap.models;

import java.util.List;

import org.commonhaus.automation.hm.namecheap.PaginationInfo;

public record DomainListResponse(
        List<DomainRecord> domains,
        PaginationInfo pagination) {
}
