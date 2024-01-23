package org.commonhaus.automation;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.github.CFGHApp;
import org.jboss.logging.Logger;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;

public class Bot {
    @Inject
    BotConfig quarkusBotConfig;

    @Inject
    CFGHApp installationManager;

    void init(@Observes StartupEvent startupEvent) {
        Log.infof("Bot started. dryRun=%s", quarkusBotConfig.isDryRun());
        try {
            installationManager.initializeCache();
        } catch (Exception e) {
            Log.error("Failed to validate", e);
        }
    }
}
