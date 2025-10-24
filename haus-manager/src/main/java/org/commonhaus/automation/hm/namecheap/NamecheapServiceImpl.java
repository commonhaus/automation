package org.commonhaus.automation.hm.namecheap;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.commonhaus.automation.hm.github.AppContextService;
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
    private final AppContextService ctx;

    NamecheapServiceImpl(NamecheapClient client, DomainContacts defaultContacts, AppContextService ctx) {
        this.client = client;
        this.defaultContacts = defaultContacts;
        this.ctx = ctx;
    }

    @Override
    public Optional<DomainContacts> getContacts(String domainName) {
        try {
            String currentXml = client.getContacts(domainName);
            DomainContacts contacts = NamecheapResponseParser.parseGetContactsResponse(currentXml);
            return Optional.of(contacts);
        } catch (NamecheapException e) {
            ctx.logAndSendEmail(ME,
                    "⛓️ Error fetching contacts for domain: %s".formatted(domainName),
                    e);
            return Optional.empty();
        }
    }

    @Override
    public boolean setContacts(String domainName, DomainContacts contacts) {
        try {
            String currentXml = client.setContacts(domainName, contacts);
            return NamecheapResponseParser.parseSetContactsResponse(currentXml);
        } catch (NamecheapException e) {
            ctx.logAndSendEmail(ME,
                    "⛓️ Error setting contacts for domain: %s".formatted(domainName),
                    e);
            return false;
        }
    }

    @Override
    public Optional<String> getDomainInfo(String domainName) {
        try {
            String result = client.getDomainInfo(domainName);
            return Optional.of(result);
        } catch (Exception e) {
            ctx.logAndSendEmail(ME,
                    "⛓️ Error fetching domain info for: %s".formatted(domainName),
                    e);
            return Optional.empty();
        }
    }

    @Override
    public List<DomainRecord> fetchAllDomains() {
        try {
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
        } catch (NamecheapException e) {
            ctx.logAndSendEmail(ME, "⛓️ Error fetching domain list from Namecheap", e);
            return List.of();
        }
    }

    @Override
    public boolean createDomain(String domainName, int years) {
        try {
            CreateDomainRequest request = new CreateDomainRequest(domainName, years, defaultContacts);
            String response = client.createDomain(request);
            NamecheapResponseParser.validateResponse(response);
            Log.infof("[%s] Successfully created domain: %s for %d year(s)", ME, domainName, years);
            return true;
        } catch (NamecheapException e) {
            ctx.logAndSendEmail(ME,
                    "⛓️ Error creating domain: %s".formatted(domainName),
                    e);
            return false;
        }
    }

    @Override
    public DomainContacts defaultContacts() {
        return defaultContacts;
    }
}
