package org.commonhaus.automation.config;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "automation")
public interface BotConfig {

    Optional<String> replyTo();

    Optional<Boolean> discoveryEnabled();

    Optional<Boolean> dryRun();

    Optional<String> errorEmailAddress();
}
