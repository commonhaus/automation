package org.commonhaus.automation.admin.config;

public class RepositoryConfig {
    protected Boolean enabled;

    protected RepositoryConfig() {
    }

    public boolean isDisabled() {
        return enabled != null && !enabled;
    }
}
