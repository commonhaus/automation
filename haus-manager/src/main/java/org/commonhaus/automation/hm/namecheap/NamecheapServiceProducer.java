package org.commonhaus.automation.hm.namecheap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import org.commonhaus.automation.hm.config.ContactConfig;
import org.commonhaus.automation.hm.config.ManagerBotConfig;
import org.commonhaus.automation.hm.config.ManagerBotConfig.NamecheapConfig;
import org.commonhaus.automation.hm.github.AppContextService;
import org.commonhaus.automation.hm.namecheap.models.ContactInfo;
import org.commonhaus.automation.hm.namecheap.models.DomainContacts;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import io.quarkus.logging.Log;

/**
 * CDI Producer for NamecheapService.
 * Creates the appropriate implementation based on whether Namecheap is configured.
 */
@ApplicationScoped
public class NamecheapServiceProducer {

    @Inject
    ManagerBotConfig mgrBotConfig;

    @Inject
    AppContextService ctx;

    /**
     * Produce NamecheapService bean.
     * Returns NoOpNamecheapService if not configured, or NamecheapServiceImpl if configured.
     *
     * @return NamecheapService implementation (application-scoped singleton)
     */
    @Produces
    @ApplicationScoped
    public NamecheapService namecheapService() {
        if (mgrBotConfig.namecheap().isEmpty()) {
            Log.info("Namecheap not configured - using no-op implementation");
            return new NoOpNamecheapService();
        }

        NamecheapConfig ncConfig = mgrBotConfig.namecheap().get();
        Log.infof("NamecheapServiceProducer: Config detected - url: %s, username: %s",
                ncConfig.url(), ncConfig.username());

        try {
            // Build REST client
            NamecheapClient client = RestClientBuilder.newBuilder()
                    .baseUri(java.net.URI.create(ncConfig.url()))
                    .followRedirects(true)
                    .register(new NamecheapClientFilter(ncConfig))
                    .build(NamecheapClient.class);

            // Build default contacts from configuration
            DomainContacts defaultContacts = buildDefaultContacts(ncConfig);

            Log.infof("Namecheap service configured for: %s", ncConfig.url());
            return new NamecheapServiceImpl(client, defaultContacts);

        } catch (Exception e) {
            Log.error("Failed to create Namecheap REST client - using no-op implementation", e);
            return new NoOpNamecheapService();
        }
    }

    /**
     * Build default contacts from bot configuration.
     * Used as the base contacts for all domain operations.
     *
     * @param ncConfig Namecheap configuration
     * @return Default domain contacts
     */
    private DomainContacts buildDefaultContacts(NamecheapConfig ncConfig) {
        ContactConfig defaultRegistrant = ncConfig.registrant(); // required

        // Validate phone format for registrant
        validatePhoneFormat("registrant", defaultRegistrant.phone());

        ContactInfo registrant = ContactInfo.fromConfig(defaultRegistrant);
        ContactInfo admin = registrant;

        ContactInfo tech = ncConfig.tech()
                .map((config) -> {
                    validatePhoneFormat("tech", config.phone());
                    return ContactInfo.fromConfig(registrant, config);
                })
                .orElse(registrant);

        ContactInfo billing = ncConfig.billing()
                .map((config) -> {
                    validatePhoneFormat("billing", config.phone());
                    return ContactInfo.fromConfig(registrant, config);
                })
                .orElse(registrant);

        return new DomainContacts(registrant, tech, admin, billing);
    }

    /**
     * Validate phone number format per Namecheap API requirements.
     * Throws IllegalStateException if invalid - this stops application startup.
     *
     * @param contactType Type of contact for error message
     * @param phone Phone number to validate
     */
    private void validatePhoneFormat(String contactType, String phone) {
        if (phone == null || phone.isBlank()) {
            throw new IllegalStateException(
                    "Phone number is required for " + contactType + " contact in bot configuration");
        }
        // Must start with +, contain exactly one dot, and have digits on both sides
        // Pattern: +NNN.NNNNNNNNNN (e.g. +1.6613102107)
        if (!phone.matches("^\\+\\d{1,3}\\.\\d{4,}$")) {
            throw new IllegalStateException(
                    "Invalid phone number format for " + contactType + " contact in bot configuration. "
                            + "Phone number must be in format +NNN.NNNNNNNNNN (e.g. +1.6613102107). Got: " + phone);
        }
    }
}
