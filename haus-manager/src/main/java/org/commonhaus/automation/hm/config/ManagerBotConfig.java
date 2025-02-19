package org.commonhaus.automation.hm.config;

import io.smallrye.config.ConfigMapping;

// Specified in application.yaml
@ConfigMapping(prefix = "automation.hausManager")
public interface ManagerBotConfig {
    // GitHub organization for configuration
    String configOrganization();

    // GitHub repository for organization configuration
    String mainRepository();
}
