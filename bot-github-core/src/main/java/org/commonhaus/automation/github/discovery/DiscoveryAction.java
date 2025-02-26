package org.commonhaus.automation.github.discovery;

public enum DiscoveryAction {
    ADDED,
    REMOVED,
    INSTALL_ADDED,
    INSTALL_REMOVED;

    public boolean added() {
        return this == ADDED || this == INSTALL_ADDED;
    }

    public boolean removed() {
        return this == REMOVED || this == INSTALL_REMOVED;
    }

    public boolean installation() {
        return this == INSTALL_ADDED || this == INSTALL_REMOVED;
    }

    public boolean repository() {
        return this == ADDED || this == REMOVED;
    }
}
