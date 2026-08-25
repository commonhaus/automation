package org.commonhaus.automation.hm.config;

import static org.commonhaus.automation.hm.namecheap.models.ContactInfo.isValidPhoneFormat;

import java.util.Optional;

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
     * This method is pure validation only. Callers own logging and notification.
     */
    public boolean isValid() {
        return validationFailure().isEmpty();
    }

    public Optional<ValidationFailure> validationFailure() {

        // Always required: firstName, lastName, emailAddress
        boolean hasRequiredFields = firstName != null && !firstName.isBlank()
                && lastName != null && !lastName.isBlank()
                && emailAddress != null && !emailAddress.isBlank();

        if (!hasRequiredFields) {
            return Optional.of(new ValidationFailure(
                    "missing required fields",
                    "Contact must have firstName, lastName, and emailAddress."));
        }

        // Phone validation: if specified, must be in valid format
        if (phone != null && !phone.isBlank() && !isValidPhoneFormat(phone)) {
            return Optional.of(new ValidationFailure(
                    "invalid phone number format",
                    "Phone number must be in format +NNN.NNNNNNNNNN (e.g. +1.6613102107)."));
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
                return Optional.of(new ValidationFailure(
                        "incomplete address",
                        "If any address field is specified, all address fields must be provided "
                                + "(address1, city, stateProvince, postalCode, country)."));
            }
        }

        return Optional.empty();
    }

    public String detailedDescription() {
        return """
                firstName: %s
                lastName: %s
                organization: %s
                jobTitle: %s
                address1: %s
                address2: %s
                city: %s
                stateProvince: %s
                postalCode: %s
                country: %s
                phone: %s
                phoneExt: %s
                fax: %s
                emailAddress: %s
                contactBase: %s
                """.formatted(
                nullToEmpty(firstName),
                nullToEmpty(lastName),
                organization.orElse(""),
                jobTitle.orElse(""),
                nullToEmpty(address1),
                address2.orElse(""),
                nullToEmpty(city),
                nullToEmpty(stateProvince),
                nullToEmpty(postalCode),
                nullToEmpty(country),
                nullToEmpty(phone),
                phoneExt.orElse(""),
                fax.orElse(""),
                nullToEmpty(emailAddress),
                contactBase.orElse(""));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record ValidationFailure(String summary, String details) {
    }
}
