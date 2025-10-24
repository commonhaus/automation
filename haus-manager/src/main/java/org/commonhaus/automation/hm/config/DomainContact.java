package org.commonhaus.automation.hm.config;

import java.util.Optional;

import org.commonhaus.automation.ContextService;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Contact configuration record.
 * Used in YAML config files (project/org) to specify domain contacts.
 * Immutable and compatible with Jackson YAML deserialization.
 */
@RegisterForReflection
public record DomainContact(
        // Required fields
        String firstName,
        String lastName,
        String address1,
        String city,
        String stateProvince,
        String postalCode,
        String country,
        String phone,
        String emailAddress,

        // Optional fields (can be null in YAML)
        Optional<String> organization,
        Optional<String> jobTitle,
        Optional<String> address2,
        Optional<String> phoneExt,
        Optional<String> fax,

        /**
         * Name of the base contact to merge with.
         * Can reference bot default contacts: "defaultRegistrantAdmin", "defaultTech", "defaultBilling"
         * or org/project level contact names.
         * If specified, this contact's null fields will be filled from the base contact.
         */
        Optional<String> contactBase) implements ContactConfig {

    /**
     * Merge this config with a base config, filling in missing (null) fields.
     * Fields from this config take priority; if null, uses base config value.
     *
     * @param base Base configuration to merge with
     * @return New DomainContact with merged values
     */
    public DomainContact mergeWith(DomainContact base) {
        if (base == null) {
            return this;
        }

        return new DomainContact(
                // Required fields - prefer this, fall back to base
                this.firstName != null ? this.firstName : base.firstName,
                this.lastName != null ? this.lastName : base.lastName,
                this.address1 != null ? this.address1 : base.address1,
                this.city != null ? this.city : base.city,
                this.stateProvince != null ? this.stateProvince : base.stateProvince,
                this.postalCode != null ? this.postalCode : base.postalCode,
                this.country != null ? this.country : base.country,
                this.phone != null ? this.phone : base.phone,
                this.emailAddress != null ? this.emailAddress : base.emailAddress,
                // Optional fields - prefer this, fall back to base
                this.organization != null ? this.organization : base.organization,
                this.jobTitle != null ? this.jobTitle : base.jobTitle,
                this.address2 != null ? this.address2 : base.address2,
                this.phoneExt != null ? this.phoneExt : base.phoneExt,
                this.fax != null ? this.fax : base.fax,
                // Don't merge contactBase - keep this contact's base reference
                this.contactBase);
    }

    /**
     * Validate that all required fields are present.
     * If invalid, logs and sends email with validation failure details.
     *
     * @param ctx Context service for logging and email
     * @param logId Log identifier
     * @param domainName Domain name for error message context
     * @return true if all required fields are present and non-blank, false otherwise
     */
    public boolean isValid(ContextService ctx, String logId, String domainName) {
        var valid = firstName != null && !firstName.isBlank()
                && lastName != null && !lastName.isBlank()
                && address1 != null && !address1.isBlank()
                && city != null && !city.isBlank()
                && stateProvince != null && !stateProvince.isBlank()
                && postalCode != null && !postalCode.isBlank()
                && country != null && !country.isBlank()
                && phone != null && !phone.isBlank()
                && emailAddress != null && !emailAddress.isBlank();

        if (!valid) {
            ctx.logAndSendEmail(logId,
                    "Invalid domain-specific tech contact for " + domainName,
                    new IllegalStateException("Contact validation failed: " + this));
        }
        return valid;
    }
}
