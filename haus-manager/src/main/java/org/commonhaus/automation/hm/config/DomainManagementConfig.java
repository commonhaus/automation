package org.commonhaus.automation.hm.config;

import java.util.List;
import java.util.Optional;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Domain management configuration for project or organization.
 * Specifies domains to manage and default contacts.
 */
@RegisterForReflection
public record DomainManagementConfig(
        Boolean enabled,
        Boolean dryRun,
        DomainContact techContact,
        List<ManagedDomain> domains) {

    public boolean isEnabled() {
        return enabled != null && enabled;
    }

    public boolean isDryRun() {
        return dryRun != null && dryRun;
    }

    public Optional<DomainContact> getTechContact() {
        return Optional.ofNullable(techContact);
    }

    @Override
    public List<ManagedDomain> domains() {
        return domains != null ? domains : List.of();
    }

    @Override
    public String toString() {
        return "DomainManagement{enabled=%s, dryRun=%s, domains=%d, hasTechContact=%s}"
                .formatted(isEnabled(), isDryRun(), domains().size(), techContact != null);
    }
}
