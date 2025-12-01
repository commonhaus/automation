package org.commonhaus.automation.hm.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.commonhaus.automation.ContextService;
import org.commonhaus.automation.config.EmailNotification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DomainContactTest {

    private ContextService ctx;
    private EmailNotification emailNotification;

    @BeforeEach
    void setup() {
        ctx = mock(ContextService.class);
        emailNotification = new EmailNotification(null, null, null);
    }

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

        boolean valid = contact.isValid(ctx, "TEST", "example.com", emailNotification);

        assertThat(valid).isTrue();
        verify(ctx, never()).logAndSendEmail(anyString(), anyString(), any(Throwable.class), any(String[].class));
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

        boolean valid = contact.isValid(ctx, "TEST", "example.com", emailNotification);

        assertThat(valid).isFalse();
        verify(ctx).logAndSendEmail(eq("TEST"), anyString(), any(Throwable.class), any(String[].class));
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

        boolean valid = contact.isValid(ctx, "TEST", "example.com", emailNotification);

        assertThat(valid).isFalse();
        verify(ctx).logAndSendEmail(eq("TEST"), anyString(), any(Throwable.class), any(String[].class));
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

        boolean valid = contact.isValid(ctx, "TEST", "example.com", emailNotification);

        assertThat(valid).isTrue();
        verify(ctx, never()).logAndSendEmail(anyString(), anyString(), any(Throwable.class), any(String[].class));
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

        boolean valid = contact.isValid(ctx, "TEST", "example.com", emailNotification);

        assertThat(valid).isFalse();
        verify(ctx).logAndSendEmail(eq("TEST"), anyString(), any(Throwable.class), any(String[].class));
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

        boolean valid = contact.isValid(ctx, "TEST", "example.com", emailNotification);

        assertThat(valid).isTrue();
        verify(ctx, never()).logAndSendEmail(anyString(), anyString(), any(Throwable.class), any(String[].class));
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

        boolean valid = contact.isValid(ctx, "TEST", "example.com", emailNotification);

        assertThat(valid).isFalse();
        verify(ctx).logAndSendEmail(eq("TEST"), anyString(), any(Throwable.class), any(String[].class));
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

        boolean valid = contact.isValid(ctx, "TEST", "example.com", emailNotification);

        assertThat(valid).isFalse();
        verify(ctx).logAndSendEmail(eq("TEST"), anyString(), any(Throwable.class), any(String[].class));
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

        boolean valid = contact.isValid(ctx, "TEST", "example.com", emailNotification);

        assertThat(valid).isTrue();
        verify(ctx, never()).logAndSendEmail(anyString(), anyString(), any(Throwable.class), any(String[].class));
    }
}
