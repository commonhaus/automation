package org.commonhaus.automation.hm.namecheap;

import java.util.List;
import java.util.Optional;

import org.commonhaus.automation.hm.namecheap.models.DomainContacts;
import org.commonhaus.automation.hm.namecheap.models.DomainRecord;

/**
 * Service for interacting with Namecheap domain registrar API.
 * Provides domain management and contact synchronization.
 */
public interface NamecheapService {

    /**
     * Get contacts for a domain.
     *
     * @param domainName Domain to fetch contacts for
     * @return Domain contacts if available, empty if error or not configured
     */
    Optional<DomainContacts> getContacts(String domainName);

    /**
     * Set contacts for a domain.
     *
     * @param domainName Domain to update
     * @param contacts New contact information
     * @return true if successful, false if error or not configured
     */
    boolean setContacts(String domainName, DomainContacts contacts);

    /**
     * Get domain information.
     *
     * @param domainName Domain to fetch info for
     * @return Domain info if available, empty if error or not configured
     */
    Optional<String> getDomainInfo(String domainName);

    /**
     * Fetch all domains from Namecheap.
     * Handles pagination automatically.
     *
     * @return List of domain records, empty list if error or not configured
     */
    List<DomainRecord> fetchAllDomains();

    /**
     * Create a domain (primarily for testing in sandbox).
     *
     * @param domainName Domain to create
     * @param years Number of years to register for
     * @return true if successful, false if error or not configured
     */
    boolean createDomain(String domainName, int years);

    /**
     * Get default contacts from configuration.
     *
     * @return Default contacts, or null if not configured
     */
    DomainContacts defaultContacts();
}
