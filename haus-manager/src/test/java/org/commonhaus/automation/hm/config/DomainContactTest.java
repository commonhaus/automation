package org.commonhaus.automation.hm.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class DomainContactTest {

    @Test
    void testValidMinimalContact() {
        // Minimal valid contact: just firstName, lastName, emailAddress
        DomainContact contact = new DomainContact(
                "John", "Doe", // required
                null, null, null, null, null, // address fields - all null is ok
                null, // phone - null is ok
                "john@example.com", // required
                Optional.of("Acme Corp"), Optional.of("Engineer"),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        boolean valid = contact.isValid();

        assertThat(valid).isTrue();
    }

    @Test
    void testInvalidMissingFirstName() {
        DomainContact contact = new DomainContact(
                null, "Doe", // missing firstName
                null, null, null, null, null,
                null,
                "john@example.com",
                Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        boolean valid = contact.isValid();

        assertThat(valid).isFalse();
    }

    @Test
    void testInvalidMissingEmailAddress() {
        DomainContact contact = new DomainContact(
                "John", "Doe",
                null, null, null, null, null,
                null,
                null, // missing emailAddress
                Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        boolean valid = contact.isValid();

        assertThat(valid).isFalse();
    }

    @Test
    void testValidContactWithPhone() {
        // Valid phone format: +NNN.NNNNNNNNNN
        DomainContact contact = new DomainContact(
                "John", "Doe",
                null, null, null, null, null,
                "+1.5551234567", // valid phone
                "john@example.com",
                Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        boolean valid = contact.isValid();

        assertThat(valid).isTrue();
    }

    @Test
    void testInvalidPhoneFormat() {
        DomainContact contact = new DomainContact(
                "John", "Doe",
                null, null, null, null, null,
                "555-1234", // invalid format
                "john@example.com",
                Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        boolean valid = contact.isValid();

        assertThat(valid).isFalse();
    }

    @Test
    void testValidContactWithFullAddress() {
        // If any address field is specified, all must be specified
        DomainContact contact = new DomainContact(
                "John", "Doe",
                "123 Main St", "Springfield", "IL", "62701", "US", // all address fields
                null,
                "john@example.com",
                Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        boolean valid = contact.isValid();

        assertThat(valid).isTrue();
    }

    @Test
    void testInvalidPartialAddress() {
        // Has address1 but missing other address fields
        DomainContact contact = new DomainContact(
                "John", "Doe",
                "123 Main St", null, null, null, null, // incomplete address
                null,
                "john@example.com",
                Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        boolean valid = contact.isValid();

        assertThat(valid).isFalse();
    }

    @Test
    void testInvalidPartialAddressMissingCity() {
        // Has some address fields but missing city
        DomainContact contact = new DomainContact(
                "John", "Doe",
                "123 Main St", null, "IL", "62701", "US", // missing city
                null,
                "john@example.com",
                Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        boolean valid = contact.isValid();

        assertThat(valid).isFalse();
    }

    @Test
    void testValidContactWithPhoneAndAddress() {
        // Full contact with both phone and address
        DomainContact contact = new DomainContact(
                "John", "Doe",
                "123 Main St", "Springfield", "IL", "62701", "US",
                "+1.5551234567",
                "john@example.com",
                Optional.of("Acme Corp"), Optional.of("Engineer"),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        boolean valid = contact.isValid();

        assertThat(valid).isTrue();
    }

}
