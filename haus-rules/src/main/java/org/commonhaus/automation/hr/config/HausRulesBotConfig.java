package org.commonhaus.automation.hr.config;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "automation.hausRules")
public interface HausRulesBotConfig {

    SchedulerConfig cron();

    interface SchedulerConfig {
        // Cron expression for periodic sync of sponsors
        Optional<String> config();

        // Cron expression for periodic sync of sponsors
        Optional<String> voting();
    }
}
