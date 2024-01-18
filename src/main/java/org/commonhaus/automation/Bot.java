package org.commonhaus.automation;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.github.CFGHApp;
import org.jboss.logging.Logger;

import io.quarkus.runtime.StartupEvent;

public class Bot {

    private static final Logger LOG = Logger.getLogger(Bot.class);

    @Inject
    BotConfig quarkusBotConfig;

    @Inject
    CFGHApp installationManager;

    void init(@Observes StartupEvent startupEvent) {
        LOG.infof("Bot started. dryRun=%s", quarkusBotConfig.isDryRun());
        try {
            installationManager.initializeCache();
        } catch (Exception e) {
            LOG.error("Failed to validate", e);
        }
    }
}
