package org.commonhaus.automation.hm.namecheap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.commonhaus.automation.hm.config.ManagerBotConfig;
import org.commonhaus.automation.hm.namecheap.models.ContactInfo;
import org.commonhaus.automation.hm.namecheap.models.DomainContacts;
import org.commonhaus.automation.hm.namecheap.models.DomainRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.quarkus.logging.Log;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

/**
 * Namecheap API integration tests.
 * Tests run against the Namecheap Sandbox API.
 *
 * IMPORTANT: All tests are @Disabled by default to prevent accidental
 * execution.
 * Enable specific tests when you want to run them manually.
 *
 * Required configuration in application.properties:
 * %dev.automation.hausManager.namecheap.url=https://api.sandbox.namecheap.com
 * %dev.automation.hausManager.namecheap.username=your-sandbox-username
 * %dev.automation.hausManager.namecheap.api-key=your-sandbox-api-key
 * %dev.automation.hausManager.namecheap.ipv4-addr=your-whitelisted-ip
 * %dev.automation.hausManager.namecheap.default-registrant-admin.first-name=...
 * %dev.automation.hausManager.namecheap.default-registrant-admin.last-name=...
 * (etc - see ManagerBotConfig.NamecheapConfig for all required fields)
 */
@QuarkusTest
@TestProfile(NamecheapClientTest.SandboxProfile.class)
@Disabled("Enable manually when setting up sandbox environment")
public class NamecheapClientTest {
    /**
     * Test profile that uses dev config to access sandbox credentials
     */
    public static class SandboxProfile implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "dev";
        }
    }

    // Define test domains we want in the sandbox
    List<String> requiredDomains = List.of(
            "commonhaus-test-update.com",
            "commonhaus.org",
            "commonhaus-update.io");

    @Inject
    ManagerBotConfig config;

    @Inject
    NamecheapService namecheapService;

    @Inject
    public MockMailbox mailbox;

    private List<String> testDomains;

    @BeforeEach
    public void setup() {
        mailbox.clear();
        testDomains = new ArrayList<>();
        if (config.namecheap().isPresent()) {
            Log.info("Namecheap client configured for testing");
        } else {
            Log.warn("Namecheap not configured - tests will use no-op service");
        }
    }

    @AfterEach
    public void tearDown() {
        await()
                .atMost(1, TimeUnit.SECONDS)
                .failFast("You've got mail:\n" + mailbox.getTotalMessagesSent(), () -> mailbox.getTotalMessagesSent() > 0)
                .until(() -> mailbox.getTotalMessagesSent() == 0);
    }

    /**
     * Test that ensures test domains exist in the sandbox.
     * Create domains if they don't exist.
     *
     * In the sandbox, you need domains to test against. This test:
     * 1. Checks if test domains exist
     * 2. Creates them if missing
     * 3. Reports what domains are available for testing
     */
    @Test
    public void ensureTestDomainsExist() {
        // Fetch all existing domains
        List<DomainRecord> domainRecords = namecheapService.fetchAllDomains();
        Log.infof("Found %d existing domains in sandbox", domainRecords.size());

        // Check each required domain
        for (String requiredDomain : requiredDomains) {
            boolean exists = domainRecords.stream()
                    .anyMatch(r -> r.name().equals(requiredDomain));

            if (exists) {
                Log.infof("✓ Domain already exists: %s", requiredDomain);
                testDomains.add(requiredDomain);
            } else {
                Log.infof("Creating missing domain: %s", requiredDomain);
                try {
                    namecheapService.createDomain(requiredDomain, 1);
                    Log.infof("✓ Successfully created domain: %s", requiredDomain);
                    testDomains.add(requiredDomain);
                } catch (Exception e) {
                    Log.errorf(e, "✗ Failed to create domain: %s", requiredDomain);
                }
            }
        }

        Log.infof("Test domains ready: %s", testDomains);
        assertThat(testDomains).isNotEmpty();
    }

    /**
     * Test getting domain contacts from Namecheap.
     * Verifies the API returns valid contact information.
     */
    @Test
    public void testGetContacts() {
        String testDomain = "commonhaus.org";
        Log.infof("Fetching contacts for: %s", testDomain);

        var contactsOpt = namecheapService.getContacts(testDomain);
        assertThat(contactsOpt).isPresent();

        DomainContacts contacts = contactsOpt.get();
        assertThat(contacts.registrant()).isNotNull();
        assertThat(contacts.tech()).isNotNull();
        assertThat(contacts.admin()).isNotNull();
        assertThat(contacts.auxBilling()).isNotNull();

        // Log contact details
        logContact("Registrant", contacts.registrant());
        logContact("Tech", contacts.tech());
        logContact("Admin", contacts.admin());
        logContact("Billing", contacts.auxBilling());
    }

    /**
     * Test set/get contacts round-trip.
     * Verifies that:
     * 1. We can fetch current contacts
     * 2. We can set contacts (even with no changes)
     * 3. The contacts remain the same after set
     */
    @Test
    public void testSetContactsRoundTrip() {
        String testDomain = "commonhaus-test-update.com";
        Log.infof("Testing setContacts round-trip for: %s", testDomain);

        // 1. Get current contacts
        var currentContactsOpt = namecheapService.getContacts(testDomain);
        assertThat(currentContactsOpt).isPresent();
        DomainContacts currentContacts = currentContactsOpt.get();
        Log.info("✓ Fetched current contacts");

        // 2. Set contacts (no changes - just verify API works)
        boolean setResult = namecheapService.setContacts(testDomain, currentContacts);
        assertThat(setResult).isTrue();
        Log.info("✓ Set contacts completed");

        // 3. Verify contacts are still the same
        var verifiedContactsOpt = namecheapService.getContacts(testDomain);
        assertThat(verifiedContactsOpt).isPresent();
        DomainContacts verifiedContacts = verifiedContactsOpt.get();

        assertThat(verifiedContacts.registrant().emailAddress())
                .isEqualTo(currentContacts.registrant().emailAddress());
        assertThat(verifiedContacts.tech().emailAddress())
                .isEqualTo(currentContacts.tech().emailAddress());
        assertThat(verifiedContacts.admin().emailAddress())
                .isEqualTo(currentContacts.admin().emailAddress());
        assertThat(verifiedContacts.auxBilling().emailAddress())
                .isEqualTo(currentContacts.auxBilling().emailAddress());

        Log.info("✓ Round-trip verification passed");
    }

    /**
     * Test checking if contacts need updating.
     * Simulates what DomainManager will do:
     * 1. Build configured contacts from bot config
     * 2. Fetch current contacts from Namecheap
     * 3. Compare to see if update is needed
     * 4. Update if different
     * 5. Verify update was successful
     */
    @Test
    public void testContactSyncDetection() {
        String testDomain = "commonhaus-update.io";
        Log.infof("Testing contact sync detection for: %s", testDomain);

        // 1. Build configured contacts (what we want)
        DomainContacts configuredContacts = namecheapService.defaultContacts();
        assertThat(configuredContacts).isNotNull();

        // 2. Fetch current contacts (what Namecheap has)
        var currentContactsOpt = namecheapService.getContacts(testDomain);
        assertThat(currentContactsOpt).isPresent();
        DomainContacts currentContacts = currentContactsOpt.get();

        // 3. Check if update is needed
        boolean needsUpdate = currentContacts.requiresUpdate(configuredContacts);
        Log.infof("Contacts need update: %s", needsUpdate);

        if (needsUpdate) {
            logContactDifferences(currentContacts, configuredContacts);

            // 4. Update contacts
            Log.info("Updating contacts...");
            boolean setResult = namecheapService.setContacts(testDomain, configuredContacts);
            assertThat(setResult).isTrue();
            Log.info("✓ Contacts updated");

            // 5. Verify update
            var verifiedContactsOpt = namecheapService.getContacts(testDomain);
            assertThat(verifiedContactsOpt).isPresent();
            DomainContacts verifiedContacts = verifiedContactsOpt.get();

            boolean stillNeedsUpdate = verifiedContacts.requiresUpdate(configuredContacts);
            assertThat(stillNeedsUpdate).isFalse();
            Log.info("✓ Contacts are now in sync");
        } else {
            Log.info("✓ Contacts are already in sync - no update needed");
        }
    }

    /**
     * Log contact information
     */
    private void logContact(String type, ContactInfo contact) {
        Log.infof("%s: %s %s <%s> (%s)",
                type,
                contact.firstName(),
                contact.lastName(),
                contact.emailAddress(),
                contact.organization().orElse("no org"));
    }

    /**
     * Log differences between two sets of contacts
     */
    private void logContactDifferences(DomainContacts current, DomainContacts configured) {
        if (!current.registrant().isSameAs(configured.registrant())) {
            Log.info("Registrant differs");
        }
        if (!current.tech().isSameAs(configured.tech())) {
            Log.info("Tech contact differs");
        }
        if (!current.admin().isSameAs(configured.admin())) {
            Log.info("Admin contact differs");
        }
        if (!current.auxBilling().isSameAs(configured.auxBilling())) {
            Log.info("Billing contact differs");
        }
    }
}
