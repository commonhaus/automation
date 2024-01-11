package org.commonhaus.automation;

import org.jboss.logging.Logger;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

public class Bot {
    
    private static final Logger LOG = Logger.getLogger(Bot.class);

    @Inject
    BotConfig quarkusBotConfig;

    void init(@Observes StartupEvent startupEvent) {
        LOG.infof("Bot started. dryRun=%s", quarkusBotConfig.isDryRun());
    }
}
