package org.commonhaus.automation.hm.namecheap;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.commonhaus.automation.hm.namecheap.models.DomainContacts;
import org.commonhaus.automation.hm.namecheap.models.DomainListResponse;
import org.commonhaus.automation.hm.namecheap.models.DomainRecord;
import org.commonhaus.automation.hm.namecheap.requests.CreateDomainRequest;

import io.quarkus.logging.Log;

/**
 * Real implementation of NamecheapService that interacts with Namecheap API.
 * Instantiated by NamecheapServiceProducer when Namecheap is configured.
 */
class NamecheapServiceImpl implements NamecheapService {
    static final String ME = "🌐-namecheap";

    private final NamecheapClient client;
    private final DomainContacts defaultContacts;

    NamecheapServiceImpl(NamecheapClient client, DomainContacts defaultContacts) {
        this.client = client;
        this.defaultContacts = defaultContacts;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public Optional<DomainContacts> getContacts(String domainName) {
        String currentXml = client.getContacts(domainName);
        Log.debugf("[%s] getContacts response for %s:\n%s", ME, domainName, currentXml);
        DomainContacts contacts = NamecheapResponseParser.parseGetContactsResponse(currentXml);
        return Optional.of(contacts);
    }

    @Override
    public boolean setContacts(String domainName, DomainContacts contacts) {
        String currentXml = client.setContacts(domainName, contacts);
        return NamecheapResponseParser.parseSetContactsResponse(currentXml);
    }

    @Override
    public Optional<String> getDomainInfo(String domainName) {
        String result = client.getDomainInfo(domainName);
        return Optional.of(result);
    }

    @Override
    public List<DomainRecord> fetchAllDomains() {
        List<DomainRecord> allDomains = new ArrayList<>();
        int currentPage = 1;
        int pageSize = 100; // Use larger page size for efficiency

        do {
            String xmlResponse = client.getDomainList(currentPage, pageSize);
            DomainListResponse response = NamecheapResponseParser.parseDomainListResponse(xmlResponse);

            allDomains.addAll(response.domains());
            PaginationInfo pagination = response.pagination();

            Log.debugf("[%s] Page %d: retrieved %d domains, total so far: %d",
                    ME, currentPage, response.domains().size(), allDomains.size());

            if (!pagination.hasMorePages()) {
                break;
            }

            currentPage = pagination.nextPage();
        } while (true);

        return allDomains;
    }

    @Override
    public boolean createDomain(String domainName, int years) {
        CreateDomainRequest request = new CreateDomainRequest(domainName, years, defaultContacts);
        String response = client.createDomain(request);
        NamecheapResponseParser.validateResponse(response);
        Log.infof("[%s] Successfully created domain: %s for %d year(s)", ME, domainName, years);
        return true;
    }

    @Override
    public DomainContacts defaultContacts() {
        return defaultContacts;
    }
}
