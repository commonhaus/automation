package org.commonhaus.automation.hm.config;

public interface LatestOrgConfig {
    OrganizationConfig getConfig();

    void notifyOnUpdate(String id, Runnable callback);
}
