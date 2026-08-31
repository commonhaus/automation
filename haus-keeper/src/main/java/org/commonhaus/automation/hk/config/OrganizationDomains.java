package org.commonhaus.automation.hk.config;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Minimal haus-keeper-local parse shape for cf-haus-organization.yml
 * (haus-manager's org config). Only the {@code projects} map is captured
 * here -- the top-level document has other fields (domainManagement,
 * teamMembership, etc.) that haus-keeper doesn't need and Jackson ignores.
 */
@RegisterForReflection
public record OrganizationDomains(Map<String, ProjectDomains> projects) {

    public OrganizationDomains {
        projects = projects != null ? projects : new HashMap<>();
    }

    /**
     * Regroup the raw (finer-grained) project entries onto the coarser
     * project-name granularity used by ProjectAliasManager, unioning
     * domains when multiple raw entries collapse onto the same derived name.
     */
    public Map<String, Set<String>> regroup(Function<String, String> toProjectName) {
        Map<String, Set<String>> regrouped = new HashMap<>();
        for (var entry : projects.entrySet()) {
            String projectName = toProjectName.apply(entry.getKey());
            List<String> domains = entry.getValue() == null ? List.of() : entry.getValue().domainAssociation();
            regrouped.computeIfAbsent(projectName, k -> new HashSet<>())
                    .addAll(domains);
        }
        return regrouped;
    }

    @RegisterForReflection
    public record ProjectDomains(
            String projectRepository,
            List<String> domainAssociation) {

        @Override
        public List<String> domainAssociation() {
            return domainAssociation == null ? List.of() : domainAssociation;
        }
    }
}
