package org.commonhaus.automation.hm.namecheap.models;

import java.time.LocalDate;

public record DomainRecord(
        String name,
        LocalDate expires, // nullable if date parsing fails
        boolean isExpired,
        boolean isLocked,
        boolean autoRenew,
        boolean isOurDNS) {
}
