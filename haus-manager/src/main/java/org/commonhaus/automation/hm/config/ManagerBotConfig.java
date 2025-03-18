package org.commonhaus.automation.hm.config;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;

// Specified in application.yaml
@ConfigMapping(prefix = "automation.hausManager")
public interface ManagerBotConfig {

    /** GitHub organization for configuration */
    HomeConfig home();

    SchedulerConfig cron();

    interface HomeConfig {

        String organization();

        String repository();
    }

    interface SchedulerConfig {

        // Cron expression for periodic sync of sponsors
        Optional<String> sponsor();

        // Cron expression for periodic sync of members
        Optional<String> projects();

        // Cron expression for periodic sync of members
        Optional<String> organization();
    }
}
