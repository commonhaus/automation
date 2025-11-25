package org.commonhaus.automation.hm.config;

import java.util.Optional;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Configuration for a single managed domain.
 * Used in domainManagement.domains list.
 */
@RegisterForReflection
public class ManagedDomain {
    /**
     * Domain name (e.g., "project.org", "example.com")
     */
    public String name;

    /**
     * Optional tech contact override for this specific domain.
     * If not specified, uses parent domainManagement.techContact or defaults.
     */
    public DomainContact techContact;

    public ManagedDomain() {
    }

    public ManagedDomain(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public Optional<DomainContact> techContact() {
        return Optional.ofNullable(techContact);
    }

    @Override
    public String toString() {
        return "ManagedDomain{name='%s', hasTechOverride=%s}".formatted(name, techContact != null);
    }
}
