package org.commonhaus.automation.hm.namecheap.requests;

import jakarta.ws.rs.QueryParam;

import org.commonhaus.automation.hm.namecheap.models.DomainContacts;

/**
 * Request bean for namecheap.domains.create API call.
 * Extends SetContactsRequest to inherit all contact field getters.
 *
 * This is primarily used for testing - creating test domains in the sandbox.
 * The Namecheap API requires 40+ parameters for domain creation.
 *
 * NOTE: Namecheap recommends using HTTP POST for this command due to the large number of parameters.
 */
public class CreateDomainRequest extends SetContactsRequest {
    private final int years;

    // Optional parameters
    private final String promotionCode;
    private final String nameservers;
    private final Boolean addFreeWhoisguard;
    private final Boolean wgEnabled;

    /**
     * Create domain registration request.
     *
     * @param domainName Domain to register (e.g., "example.com")
     * @param years Number of years to register (1-10)
     * @param contacts All 4 contact types required
     */
    public CreateDomainRequest(String domainName, int years, DomainContacts contacts) {
        this(domainName, years, contacts, null, null, null, null);
    }

    /**
     * Full constructor with optional parameters.
     */
    public CreateDomainRequest(
            String domainName,
            int years,
            DomainContacts contacts,
            String promotionCode,
            String nameservers,
            Boolean addFreeWhoisguard,
            Boolean wgEnabled) {

        super(domainName, contacts);

        if (years < 1 || years > 10) {
            throw new IllegalArgumentException("Years must be between 1 and 10");
        }

        this.years = years;
        this.promotionCode = promotionCode;
        this.nameservers = nameservers;
        this.addFreeWhoisguard = addFreeWhoisguard;
        this.wgEnabled = wgEnabled;
    }

    // Override command for domain creation

    @Override
    public String getCommand() {
        return "namecheap.domains.create";
    }

    // Additional parameters for domain creation

    @QueryParam("Years")
    public int getYears() {
        return years;
    }

    @QueryParam("PromotionCode")
    public String getPromotionCode() {
        return promotionCode;
    }

    @QueryParam("Nameservers")
    public String getNameservers() {
        return nameservers;
    }

    @QueryParam("AddFreeWhoisguard")
    public String getAddFreeWhoisguard() {
        return addFreeWhoisguard != null ? (addFreeWhoisguard ? "yes" : "no") : null;
    }

    @QueryParam("WGEnabled")
    public String getWGEnabled() {
        return wgEnabled != null ? (wgEnabled ? "yes" : "no") : null;
    }
}
