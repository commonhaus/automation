package org.commonhaus.automation.hm.namecheap;

import java.util.List;
import java.util.Optional;

import org.commonhaus.automation.hm.namecheap.models.DomainContacts;
import org.commonhaus.automation.hm.namecheap.models.DomainRecord;

import io.quarkus.logging.Log;

/**
 * No-op implementation of NamecheapService used when Namecheap is not configured.
 * All methods log a debug message and return empty/false results.
 */
class NoOpNamecheapService implements NamecheapService {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public Optional<DomainContacts> getContacts(String domainName) {
        Log.debugf("Namecheap not configured - skipping getContacts for %s", domainName);
        return Optional.empty();
    }

    @Override
    public boolean setContacts(String domainName, DomainContacts contacts) {
        Log.debugf("Namecheap not configured - skipping setContacts for %s", domainName);
        return false;
    }

    @Override
    public Optional<String> getDomainInfo(String domainName) {
        Log.debugf("Namecheap not configured - skipping getDomainInfo for %s", domainName);
        return Optional.empty();
    }

    @Override
    public List<DomainRecord> fetchAllDomains() {
        Log.debug("Namecheap not configured - skipping fetchAllDomains");
        return List.of();
    }

    @Override
    public boolean createDomain(String domainName, int years) {
        Log.debugf("Namecheap not configured - skipping createDomain for %s", domainName);
        return false;
    }

    @Override
    public DomainContacts defaultContacts() {
        Log.debug("Namecheap not configured - no default contacts available");
        return null;
    }
}
