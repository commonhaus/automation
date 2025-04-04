package org.commonhaus.automation.hk.config;

import java.net.URI;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "automation.hausKeeper")
public interface AdminBotConfig {

    URI memberHome();

    /** GitHub organization for configuration */
    HomeConfig home();

    SchedulerConfig cron();

    interface HomeConfig {
        String organization();

        String datastore();
    }

    interface SchedulerConfig {
        Optional<String> projectAliases();

        Optional<String> verifyLogins();
    }
}
