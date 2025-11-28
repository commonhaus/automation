package org.commonhaus.automation.hm.namecheap.models;

import java.util.List;

import org.commonhaus.automation.hm.namecheap.PaginationInfo;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record DomainListResponse(
        List<DomainRecord> domains,
        PaginationInfo pagination) {
}
