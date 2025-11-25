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

        ContactInfo registrant = ContactInfo.fromConfig(defaultRegistrant);
        ContactInfo admin = registrant;

        ContactInfo tech = ncConfig.tech()
                .map((config) -> ContactInfo.fromConfig(registrant, config))
                .orElse(registrant);

        ContactInfo billing = ncConfig.billing()
                .map((config) -> ContactInfo.fromConfig(registrant, config))
                .orElse(registrant);

        return new DomainContacts(registrant, tech, admin, billing);
    }
}
