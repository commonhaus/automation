package org.commonhaus.automation;

import java.util.Optional;

import jakarta.inject.Singleton;

import org.commonhaus.automation.github.discovery.DiscoveryConfig;

import io.smallrye.config.ConfigMapping;

@Singleton
public class AppConfig implements DiscoveryConfig {

    @ConfigMapping(prefix = "automation")
    interface Data {
        Optional<String> replyTo();

        Optional<String> cronExpr();

        Optional<Boolean> discoveryEnabled();

        Optional<Boolean> dryRun();
    }

    private final Data data;

    public AppConfig(Data data) {
        this.data = data;
    }

    public Optional<String> replyTo() {
        return data.replyTo();
    }

    public Optional<String> cronExpr() {
        return data.cronExpr();
    }

    public boolean isDiscoveryEnabled() {
        Optional<Boolean> discoveryEnabled = data.discoveryEnabled();
        return discoveryEnabled.isEmpty() || discoveryEnabled.get();
    }

    public boolean isDryRun() {
        Optional<Boolean> dryRun = data.dryRun();
        return dryRun.isPresent() && dryRun.get();
    }

    public Class<RepositoryConfigFile> getConfigType() {
        return RepositoryConfigFile.class;
    }

    public String getConfigFileName() {
        return RepositoryConfigFile.NAME;
    }
}
