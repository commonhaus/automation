package org.commonhaus.automation.hm.config;

import static org.commonhaus.automation.hm.namecheap.models.ContactInfo.isValidPhoneFormat;

import java.util.Optional;

import org.commonhaus.automation.ContextService;
import org.commonhaus.automation.config.EmailNotification;

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
         * Can reference bot default contacts: "defaultRegistrantAdmin", "defaultTech",
         * "defaultBilling"
         * or org/project level contact names.
         * If specified, this contact's null fields will be filled from the base
         * contact.
         */
        Optional<String> contactBase) implements ContactConfig {

    public DomainContact {
        organization = organization == null ? Optional.empty() : organization;
        jobTitle = jobTitle == null ? Optional.empty() : jobTitle;
        address2 = address2 == null ? Optional.empty() : address2;
        phoneExt = phoneExt == null ? Optional.empty() : phoneExt;
        fax = fax == null ? Optional.empty() : fax;
        contactBase = contactBase == null ? Optional.empty() : contactBase;
    }

    /**
     * Validate that required fields are present based on what's specified.
     *
     * Validation rules for project tech contacts:
     * - Always required: firstName, lastName, emailAddress
     * - Phone: optional, but if specified must be in valid format
     * - Address fields: optional as a group, but if ANY address field is specified,
     * then ALL address fields must be specified (address1, city, stateProvince,
     * postalCode, country)
     * - Missing fields will be filled from default tech contact via merging
     *
     * If invalid, logs and sends email with validation failure details.
     *
     * @param ctx Context service for logging and email
     * @param logId Log identifier
     * @param domainName Domain name for error message context
     * @param emailNotification
     * @return true if validation passes, false otherwise
     */
    public boolean isValid(ContextService ctx, String logId, String domainName,
            EmailNotification emailNotification) {

        // Always required: firstName, lastName, emailAddress
        boolean hasRequiredFields = firstName != null && !firstName.isBlank()
                && lastName != null && !lastName.isBlank()
                && emailAddress != null && !emailAddress.isBlank();

        if (!hasRequiredFields) {
            ctx.logAndSendEmail(logId,
                    "Invalid tech contact for " + domainName + ": missing required fields",
                    new IllegalStateException(
                            "Contact must have firstName, lastName, and emailAddress. Got: " + this),
                    emailNotification.errors());
            return false;
        }

        // Phone validation: if specified, must be in valid format
        if (phone != null && !phone.isBlank() && !isValidPhoneFormat(phone)) {
            ctx.logAndSendEmail(logId,
                    "Invalid phone number format for " + domainName,
                    new IllegalStateException(
                            "Phone number must be in format +NNN.NNNNNNNNNN (e.g. +1.6613102107). Got: " + phone),
                    emailNotification.errors());
            return false;
        }

        // Address validation: if ANY address field is specified, ALL must be specified
        boolean hasAnyAddress = (address1 != null && !address1.isBlank())
                || (city != null && !city.isBlank())
                || (stateProvince != null && !stateProvince.isBlank())
                || (postalCode != null && !postalCode.isBlank())
                || (country != null && !country.isBlank());

        if (hasAnyAddress) {
            boolean hasAllAddress = address1 != null && !address1.isBlank()
                    && city != null && !city.isBlank()
                    && stateProvince != null && !stateProvince.isBlank()
                    && postalCode != null && !postalCode.isBlank()
                    && country != null && !country.isBlank();

            if (!hasAllAddress) {
                ctx.logAndSendEmail(logId,
                        "Invalid tech contact for " + domainName + ": incomplete address",
                        new IllegalStateException(
                                "If any address field is specified, all address fields must be provided "
                                        + "(address1, city, stateProvince, postalCode, country). Got: " + this),
                        emailNotification.errors());
                return false;
            }
        }

        return true;
    }
}
