package org.commonhaus.automation.hm.namecheap.requests;

import jakarta.ws.rs.QueryParam;

import org.commonhaus.automation.hm.namecheap.models.ContactInfo;
import org.commonhaus.automation.hm.namecheap.models.DomainContacts;

/**
 * Request bean for namecheap.domains.setContacts API call.
 * Uses @BeanParam pattern with annotated getters.
 *
 * The Namecheap API requires ~40+ query parameters to set contacts.
 * This class stores the clean DomainContacts model and exposes getters
 * that delegate to the underlying ContactInfo records.
 */
public class SetContactsRequest extends BaseRequest {
    // Store clean domain objects (not individual fields!)
    private final String domainName;
    private final ContactInfo registrant;
    private final ContactInfo tech;
    private final ContactInfo admin;
    private final ContactInfo auxBilling;

    /**
     * Create request from domain name and contacts.
     *
     * @param domainName Domain to update
     * @param contacts All 4 contact types
     */
    public SetContactsRequest(String domainName, DomainContacts contacts) {
        if (domainName == null || domainName.isBlank()) {
            throw new IllegalArgumentException("Domain name cannot be null or blank");
        }
        if (contacts == null) {
            throw new IllegalArgumentException("Contacts cannot be null");
        }

        this.domainName = domainName;
        this.registrant = contacts.registrant();
        this.tech = contacts.tech();
        this.admin = contacts.admin();
        this.auxBilling = contacts.auxBilling();
    }

    // Command and domain name

    @Override
    public String getCommand() {
        return "namecheap.domains.setContacts";
    }

    @QueryParam("DomainName")
    public String getDomainName() {
        return domainName;
    }

    // Registrant contact fields (all delegate to registrant ContactInfo)

    @QueryParam("RegistrantFirstName")
    public String getRegistrantFirstName() {
        return registrant.firstName();
    }

    @QueryParam("RegistrantLastName")
    public String getRegistrantLastName() {
        return registrant.lastName();
    }

    @QueryParam("RegistrantAddress1")
    public String getRegistrantAddress1() {
        return registrant.address1();
    }

    @QueryParam("RegistrantCity")
    public String getRegistrantCity() {
        return registrant.city();
    }

    @QueryParam("RegistrantStateProvince")
    public String getRegistrantStateProvince() {
        return registrant.stateProvince();
    }

    @QueryParam("RegistrantPostalCode")
    public String getRegistrantPostalCode() {
        return registrant.postalCode();
    }

    @QueryParam("RegistrantCountry")
    public String getRegistrantCountry() {
        return registrant.country();
    }

    @QueryParam("RegistrantPhone")
    public String getRegistrantPhone() {
        return registrant.phone();
    }

    @QueryParam("RegistrantEmailAddress")
    public String getRegistrantEmailAddress() {
        return registrant.emailAddress();
    }

    // Optional registrant fields (return null if not present)

    @QueryParam("RegistrantOrganizationName")
    public String getRegistrantOrganizationName() {
        return registrant.organization().orElse(null);
    }

    @QueryParam("RegistrantJobTitle")
    public String getRegistrantJobTitle() {
        return registrant.jobTitle().orElse(null);
    }

    @QueryParam("RegistrantAddress2")
    public String getRegistrantAddress2() {
        return registrant.address2().orElse(null);
    }

    @QueryParam("RegistrantPhoneExt")
    public String getRegistrantPhoneExt() {
        return registrant.phoneExt().orElse(null);
    }

    @QueryParam("RegistrantFax")
    public String getRegistrantFax() {
        return registrant.fax().orElse(null);
    }

    // Tech contact fields (all delegate to tech ContactInfo)

    @QueryParam("TechFirstName")
    public String getTechFirstName() {
        return tech.firstName();
    }

    @QueryParam("TechLastName")
    public String getTechLastName() {
        return tech.lastName();
    }

    @QueryParam("TechAddress1")
    public String getTechAddress1() {
        return tech.address1();
    }

    @QueryParam("TechCity")
    public String getTechCity() {
        return tech.city();
    }

    @QueryParam("TechStateProvince")
    public String getTechStateProvince() {
        return tech.stateProvince();
    }

    @QueryParam("TechPostalCode")
    public String getTechPostalCode() {
        return tech.postalCode();
    }

    @QueryParam("TechCountry")
    public String getTechCountry() {
        return tech.country();
    }

    @QueryParam("TechPhone")
    public String getTechPhone() {
        return tech.phone();
    }

    @QueryParam("TechEmailAddress")
    public String getTechEmailAddress() {
        return tech.emailAddress();
    }

    // Optional tech fields

    @QueryParam("TechOrganizationName")
    public String getTechOrganizationName() {
        return tech.organization().orElse(null);
    }

    @QueryParam("TechJobTitle")
    public String getTechJobTitle() {
        return tech.jobTitle().orElse(null);
    }

    @QueryParam("TechAddress2")
    public String getTechAddress2() {
        return tech.address2().orElse(null);
    }

    @QueryParam("TechPhoneExt")
    public String getTechPhoneExt() {
        return tech.phoneExt().orElse(null);
    }

    @QueryParam("TechFax")
    public String getTechFax() {
        return tech.fax().orElse(null);
    }

    // Admin contact fields (all delegate to admin ContactInfo)

    @QueryParam("AdminFirstName")
    public String getAdminFirstName() {
        return admin.firstName();
    }

    @QueryParam("AdminLastName")
    public String getAdminLastName() {
        return admin.lastName();
    }

    @QueryParam("AdminAddress1")
    public String getAdminAddress1() {
        return admin.address1();
    }

    @QueryParam("AdminCity")
    public String getAdminCity() {
        return admin.city();
    }

    @QueryParam("AdminStateProvince")
    public String getAdminStateProvince() {
        return admin.stateProvince();
    }

    @QueryParam("AdminPostalCode")
    public String getAdminPostalCode() {
        return admin.postalCode();
    }

    @QueryParam("AdminCountry")
    public String getAdminCountry() {
        return admin.country();
    }

    @QueryParam("AdminPhone")
    public String getAdminPhone() {
        return admin.phone();
    }

    @QueryParam("AdminEmailAddress")
    public String getAdminEmailAddress() {
        return admin.emailAddress();
    }

    // Optional admin fields

    @QueryParam("AdminOrganizationName")
    public String getAdminOrganizationName() {
        return admin.organization().orElse(null);
    }

    @QueryParam("AdminJobTitle")
    public String getAdminJobTitle() {
        return admin.jobTitle().orElse(null);
    }

    @QueryParam("AdminAddress2")
    public String getAdminAddress2() {
        return admin.address2().orElse(null);
    }

    @QueryParam("AdminPhoneExt")
    public String getAdminPhoneExt() {
        return admin.phoneExt().orElse(null);
    }

    @QueryParam("AdminFax")
    public String getAdminFax() {
        return admin.fax().orElse(null);
    }

    // AuxBilling contact fields (all delegate to auxBilling ContactInfo)

    @QueryParam("AuxBillingFirstName")
    public String getAuxBillingFirstName() {
        return auxBilling.firstName();
    }

    @QueryParam("AuxBillingLastName")
    public String getAuxBillingLastName() {
        return auxBilling.lastName();
    }

    @QueryParam("AuxBillingAddress1")
    public String getAuxBillingAddress1() {
        return auxBilling.address1();
    }

    @QueryParam("AuxBillingCity")
    public String getAuxBillingCity() {
        return auxBilling.city();
    }

    @QueryParam("AuxBillingStateProvince")
    public String getAuxBillingStateProvince() {
        return auxBilling.stateProvince();
    }

    @QueryParam("AuxBillingPostalCode")
    public String getAuxBillingPostalCode() {
        return auxBilling.postalCode();
    }

    @QueryParam("AuxBillingCountry")
    public String getAuxBillingCountry() {
        return auxBilling.country();
    }

    @QueryParam("AuxBillingPhone")
    public String getAuxBillingPhone() {
        return auxBilling.phone();
    }

    @QueryParam("AuxBillingEmailAddress")
    public String getAuxBillingEmailAddress() {
        return auxBilling.emailAddress();
    }

    // Optional auxBilling fields

    @QueryParam("AuxBillingOrganizationName")
    public String getAuxBillingOrganizationName() {
        return auxBilling.organization().orElse(null);
    }

    @QueryParam("AuxBillingJobTitle")
    public String getAuxBillingJobTitle() {
        return auxBilling.jobTitle().orElse(null);
    }

    @QueryParam("AuxBillingAddress2")
    public String getAuxBillingAddress2() {
        return auxBilling.address2().orElse(null);
    }

    @QueryParam("AuxBillingPhoneExt")
    public String getAuxBillingPhoneExt() {
        return auxBilling.phoneExt().orElse(null);
    }

    @QueryParam("AuxBillingFax")
    public String getAuxBillingFax() {
        return auxBilling.fax().orElse(null);
    }
}
