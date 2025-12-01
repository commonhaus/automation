package org.commonhaus.automation.hm.namecheap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.commonhaus.automation.hm.namecheap.models.ContactInfo;
import org.junit.jupiter.api.Test;

class ContactInfoMergeTest {

    @Test
    void testMergeMinimalProjectContact() {
        // Base: default contact with all fields
        ContactInfo base = new ContactInfo(
                "Default", "Admin",
                "100 Default St", "Default City", "CA", "90000", "US",
                "+1.8001112222", "default@example.com",
                Optional.of("Default Org"),
                Optional.of("Default Role"),
                Optional.of("Suite 100"),
                Optional.of("100"),
                Optional.empty(),
                false);

        // Override: project contact with minimal fields (firstName, lastName, email, org, jobTitle)
        ContactInfo override = new ContactInfo(
                "Hibernate", "Admins",
                null, null, null, null, null, // no address
                null, // no phone
                "admins@example.com",
                Optional.of("Project Name at Foundation"),
                Optional.of("Operations"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false);

        ContactInfo merged = base.mergeWith(override);

        // Project-specified fields should override
        assertThat(merged.firstName()).isEqualTo("Hibernate");
        assertThat(merged.lastName()).isEqualTo("Admins");
        assertThat(merged.emailAddress()).isEqualTo("admins@example.com");
        assertThat(merged.organization()).isEqualTo(Optional.of("Project Name at Foundation"));
        assertThat(merged.jobTitle()).isEqualTo(Optional.of("Operations"));

        // Default fields should be preserved (phone and address)
        assertThat(merged.phone()).isEqualTo("+1.8001112222");
        assertThat(merged.phoneExt()).isEqualTo(Optional.of("100")); // kept from base
        assertThat(merged.address1()).isEqualTo("100 Default St");
        assertThat(merged.address2()).isEqualTo(Optional.of("Suite 100")); // kept from base
        assertThat(merged.city()).isEqualTo("Default City");
        assertThat(merged.stateProvince()).isEqualTo("CA");
        assertThat(merged.postalCode()).isEqualTo("90000");
        assertThat(merged.country()).isEqualTo("US");
    }

    @Test
    void testMergeWithAddressOverride() {
        // Base with address and address2
        ContactInfo base = new ContactInfo(
                "Default", "Admin",
                "100 Default St",
                "Default City",
                "CA",
                "90000",
                "US",
                "+1.8001112222",
                "default@example.com",
                Optional.of("Default Org"),
                Optional.of("Default Role"),
                Optional.of("Suite 100"),
                Optional.of("100"),
                Optional.empty(),
                false);

        // Override with new address but no address2
        ContactInfo override = new ContactInfo(
                "Project", "Contact",
                "456 Project Ave",
                "Project City",
                "NY",
                "10001",
                "US", // new address
                null,
                "project@example.com",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false);

        ContactInfo merged = base.mergeWith(override);

        // Should use override's address
        assertThat(merged.address1()).isEqualTo("456 Project Ave");
        assertThat(merged.city()).isEqualTo("Project City");
        assertThat(merged.stateProvince()).isEqualTo("NY");
        assertThat(merged.postalCode()).isEqualTo("10001");
        assertThat(merged.country()).isEqualTo("US");

        // IMPORTANT: Should NOT keep base's address2 or phoneExt with override's address1
        assertThat(merged.address2()).isEqualTo(Optional.empty());
        // IMPORTANT: Phone was not overridden, so phoneExt should remain from base
        assertThat(merged.phoneExt()).isEqualTo(Optional.of("100"));
    }
}
