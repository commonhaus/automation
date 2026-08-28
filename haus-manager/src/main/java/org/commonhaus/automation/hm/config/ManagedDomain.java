package org.commonhaus.automation.hm.config;

import java.util.Optional;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Configuration for a single managed domain.
 * Used in domainManagement.domains list.
 */
@RegisterForReflection
public record ManagedDomain(
        String name,
        DomainContact techContact) {

    public ManagedDomain(String name) {
        this(name, null);
    }

    public Optional<DomainContact> getTechContact() {
        return Optional.ofNullable(techContact);
    }

    @Override
    public String toString() {
        return "ManagedDomain{name='%s', hasTechOverride=%s}".formatted(name, techContact != null);
    }
}
