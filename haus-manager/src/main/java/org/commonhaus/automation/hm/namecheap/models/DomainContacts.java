package org.commonhaus.automation.hm.namecheap.models;

/**
 * Complete set of domain contacts (all 4 roles).
 * Namecheap requires Registrant, Tech, Admin, and AuxBilling contacts for each domain.
 */
public record DomainContacts(
        ContactInfo registrant,
        ContactInfo tech,
        ContactInfo admin,
        ContactInfo auxBilling) {

    /**
     * Check if any contacts have changed compared to current contacts.
     * Used to detect if an update is needed (avoid unnecessary API calls).
     *
     * @param current Current domain contacts from Namecheap
     * @return true if any contact differs, false if all are the same
     */
    public boolean requiresUpdate(DomainContacts current) {
        if (current == null) {
            return true;
        }

        return !registrant.isSameAs(current.registrant) ||
                !tech.isSameAs(current.tech) ||
                !admin.isSameAs(current.admin) ||
                !auxBilling.isSameAs(current.auxBilling);
    }
}
