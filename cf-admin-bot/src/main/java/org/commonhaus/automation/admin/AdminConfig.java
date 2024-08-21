package org.commonhaus.automation.admin;

import java.net.URI;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "automation.admin")
public interface AdminConfig {
    Optional<String> teamSyncCron();

    String datastore();

    URI memberHome();
}
