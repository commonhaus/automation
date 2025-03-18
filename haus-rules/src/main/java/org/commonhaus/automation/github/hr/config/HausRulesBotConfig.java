package org.commonhaus.automation.github.hr.config;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "automation.hausRules")
public interface HausRulesBotConfig {
    interface SchedulerConfig {

        // Cron expression for periodic sync of sponsors
        Optional<String> voting();
    }
}
