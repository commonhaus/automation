package org.commonhaus.automation.github.discovery;

public interface DiscoveryConfig {
    boolean isDiscoveryEnabled();

    boolean isDryRun();

    Class<?> getConfigType();

    String getConfigFileName();
}
