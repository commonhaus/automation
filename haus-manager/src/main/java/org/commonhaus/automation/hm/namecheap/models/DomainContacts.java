package org.commonhaus.automation.hm.namecheap.models;

/**
 * Complete set of domain contacts (all 4 roles).
 * Namecheap requires Registrant, Tech, Admin, and AuxBilling contacts for each domain.
 * However, some TLDs only accept a subset of these contacts (e.g., .to only accepts Registrant).
 *
 * The hasXxx flags track which contacts were actually present in the API response,
 * so we only compare and send back the contacts that the TLD supports.
 */
public record DomainContacts(
        ContactInfo registrant,
        ContactInfo tech,
        ContactInfo admin,
        ContactInfo auxBilling,
        boolean hasTech,
        boolean hasAdmin,
        boolean hasAuxBilling) {

    /**
     * Create DomainContacts with all contact types present (standard case).
     */
    public DomainContacts(ContactInfo registrant, ContactInfo tech, ContactInfo admin, ContactInfo auxBilling) {
        this(registrant, tech, admin, auxBilling, true, true, true);
    }

    /**
     * Check if any contacts have changed compared to current contacts.
     * Used to detect if an update is needed (avoid unnecessary API calls).
     * Only compares contact types that are actually supported by the TLD.
     *
     * @param other Current domain contacts from Namecheap
     * @return true if any contact differs, false if all are the same
     */
    public boolean requiresUpdate(DomainContacts other) {
        if (other == null) {
            return true;
        }

        // Always compare registrant (required for all domains)
        if (!registrant.isSameAs(other.registrant)) {
            return true;
        }

        // Only compare tech contact if the TLD supports it
        if (other.hasTech && !tech.isSameAs(other.tech)) {
            return true;
        }

        // Only compare admin contact if the TLD supports it
        if (other.hasAdmin && !admin.isSameAs(other.admin)) {
            return true;
        }

        // Only compare billing contact if the TLD supports it
        if (other.hasAuxBilling && !auxBilling.isSameAs(other.auxBilling)) {
            return true;
        }

        return false;
    }

    public String prettyString() {
        return """
                Registrant:
                %s

                Tech:
                %s

                Admin:
                %s

                AuxBilling:
                %s
                """.formatted(
                registrant.prettyString(),
                tech.prettyString(),
                admin.prettyString(),
                auxBilling.prettyString());
    }
}
