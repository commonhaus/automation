package org.commonhaus.automation.hm.namecheap.models;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Complete domain information including registration details and contacts.
 * Used for reporting and workflow dispatch payloads.
 *
 * Combines data from NamecheapService.fetchAllDomains() and NamecheapService.getContacts()
 * Both record and contacts are required - this object should only be created when both are available.
 */
@RegisterForReflection
public record DomainInformation(
        @JsonUnwrapped DomainRecord record,
        DomainContacts contacts) {

    /**
     * Get the domain name
     */
    public String name() {
        return record.name();
    }

    /**
     * Get the expiration date
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    public LocalDate expires() {
        return record.expires();
    }

    /**
     * Check if domain is expired
     */
    public boolean isExpired() {
        return record.isExpired();
    }

    /**
     * Check if domain is locked
     */
    public boolean isLocked() {
        return record.isLocked();
    }

    /**
     * Check if domain has auto-renew enabled
     */
    public boolean autoRenew() {
        return record.autoRenew();
    }

    /**
     * Check if domain uses Namecheap DNS
     */
    public boolean isOurDNS() {
        return record.isOurDNS();
    }

    /**
     * Get the domain contacts
     */
    public DomainContacts contacts() {
        return contacts;
    }
}
