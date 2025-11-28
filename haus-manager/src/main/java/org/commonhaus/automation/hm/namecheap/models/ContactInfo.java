package org.commonhaus.automation.hm.namecheap.models;

import java.util.Optional;

import org.commonhaus.automation.hm.config.ContactConfig;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Immutable contact information for domain registration.
 * Represents a single contact (Registrant, Tech, Admin, or Billing).
 */
@RegisterForReflection
public record ContactInfo(
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

        // Optional fields
        Optional<String> organization,
        Optional<String> jobTitle,
        Optional<String> address2,
        Optional<String> phoneExt,
        Optional<String> fax,

        boolean readOnly) {

    public static ContactInfo fromConfig(ContactInfo base, ContactConfig config) {
        return base.mergeWith(fromConfig(config));
    }

    /**
     * Create ContactInfo from configuration.
     *
     * @param config Contact configuration from YAML or bot config
     * @return ContactInfo instance
     */
    public static ContactInfo fromConfig(ContactConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("ContactConfig cannot be null");
        }

        return new ContactInfo(
                config.firstName(),
                config.lastName(),
                config.address1(),
                config.city(),
                config.stateProvince(),
                config.postalCode(),
                config.country(),
                config.phone(),
                config.emailAddress(),
                config.organization(),
                config.jobTitle(),
                config.address2(),
                config.phoneExt(),
                config.fax(),
                false // Not read-only when from config
        );
    }

    /**
     * Merge this contact with an override, preferring non-null values from
     * override.
     * Useful for applying partial overrides from more specific configurations.
     *
     * @param override Contact to merge with (higher priority)
     * @return New ContactInfo with merged values
     */
    public ContactInfo mergeWith(ContactInfo override) {
        if (override == null) {
            return this;
        }

        return new ContactInfo(
                override.firstName != null ? override.firstName : this.firstName,
                override.lastName != null ? override.lastName : this.lastName,
                override.address1 != null ? override.address1 : this.address1,
                override.city != null ? override.city : this.city,
                override.stateProvince != null ? override.stateProvince : this.stateProvince,
                override.postalCode != null ? override.postalCode : this.postalCode,
                override.country != null ? override.country : this.country,
                override.phone != null ? override.phone : this.phone,
                override.emailAddress != null ? override.emailAddress : this.emailAddress,
                override.organization.isPresent() ? override.organization : this.organization,
                override.jobTitle.isPresent() ? override.jobTitle : this.jobTitle,
                override.address2.isPresent() ? override.address2 : this.address2,
                override.phoneExt.isPresent() ? override.phoneExt : this.phoneExt,
                override.fax.isPresent() ? override.fax : this.fax,
                this.readOnly // Preserve readOnly status
        );
    }

    /**
     * Check if this contact is functionally the same as another (ignoring readOnly
     * flag).
     * Used to detect if contact information has changed.
     *
     * @param other Contact to compare with
     * @return true if all contact fields match
     */
    public boolean isSameAs(ContactInfo other) {
        if (other == null) {
            return false;
        }

        return equals(firstName, other.firstName) &&
                equals(lastName, other.lastName) &&
                equals(address1, other.address1) &&
                equals(city, other.city) &&
                equals(stateProvince, other.stateProvince) &&
                equals(postalCode, other.postalCode) &&
                equals(country, other.country) &&
                equals(phone, other.phone) &&
                equals(emailAddress, other.emailAddress) &&
                equals(organization, other.organization) &&
                equals(jobTitle, other.jobTitle) &&
                equals(address2, other.address2) &&
                equals(phoneExt, other.phoneExt) &&
                equals(fax, other.fax);
    }

    private static boolean equals(Object a, Object b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b);
    }

    public Object prettyString() {
        return """
                - Organization: %s
                - Job Title: %s
                - %s %s
                - Address1: %s
                - Address2: %s
                - City: %s
                - State/Province: %s
                - Postal Code: %s
                - Country: %s
                - Email Address: %s
                """.formatted(
                organization.orElse(""),
                jobTitle.orElse(""),
                firstName,
                lastName,
                address1,
                address2.orElse(""),
                city,
                stateProvince,
                postalCode,
                country,
                emailAddress);
    }

    /**
     * Validate phone number format per Namecheap API requirements.
     * Format: +{country code}.{phone number} (e.g. +1.6613102107)
     *
     * @param phone Phone number to validate
     * @return true if format is valid
     */
    public static boolean isValidPhoneFormat(String phone) {
        if (phone == null || phone.isBlank()) {
            return false;
        }
        // Must start with +, contain exactly one dot, and have digits on both sides
        // Pattern: +NNN.NNNNNNNNNN per Namecheap API documentation
        return phone.matches("^\\+\\d{1,3}\\.\\d{4,}$");
    }
}
