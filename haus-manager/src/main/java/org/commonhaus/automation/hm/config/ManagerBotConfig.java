package org.commonhaus.automation.hm.config;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;

// Specified in application.yaml
@ConfigMapping(prefix = "automation.hausManager")
public interface ManagerBotConfig {

    // GitHub organization for configuration
    String configOrganization();

    // GitHub repository for organization configuration
    String mainRepository();

    // Cron expression for periodic sync of sponsors
    Optional<String> sponsorCron();

    // Cron expression for periodic sync of members
    Optional<String> cron();
}
