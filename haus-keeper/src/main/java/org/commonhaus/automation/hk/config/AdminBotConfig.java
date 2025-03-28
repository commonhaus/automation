package org.commonhaus.automation.hk.config;

import java.net.URI;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "automation.hausKeeper")
public interface AdminBotConfig {
    Optional<String> teamSyncCron();

    String datastore();

    URI memberHome();
}
