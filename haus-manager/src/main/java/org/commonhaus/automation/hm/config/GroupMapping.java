package org.commonhaus.automation.hm.config;

import java.util.List;
import java.util.Map;

import org.commonhaus.automation.config.RepoSource;
import org.commonhaus.automation.hm.config.OrganizationConfig.OrgDefaults;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * File to read as source of groups and their members.
 * The file should contain a list of groups, each with a list of members.
 *
 * @param source Path to the source file (usually CONTACTS.yaml)
 * @param mapPointer JSON Pointer / reference path to list of groups
 * @param defaults Common fallback values for team configuration
 * @param pushMembers Mapping of groups to teams and their members
 * @param dryRun If true, do not update team or organization membership
 */
@RegisterForReflection
public record GroupMapping(
        RepoSource source,
        String mapPointer,
        OrgDefaults defaults,
        Map<String, PushToTeams> pushMembers,
        Boolean dryRun) {

    public boolean performSync() {
        return pushMembers() != null && source() != null && !source().isEmpty();
    }

    @Override
    public String mapPointer() {
        return mapPointer == null ? "" : mapPointer;
    }

    public List<String> watchedTeams(String org) {
        return pushMembers().values().stream()
                .flatMap(x -> x.teams().stream())
                .map(x -> OrganizationConfig.toFullTeamName(org, x))
                .toList();
    }

    @Override
    public Boolean dryRun() {
        return dryRun != null && dryRun;
    }

    @Override
    public String toString() {
        return "GroupMapping{dryRun=%s, source='%s', mapPointer='%s', defaults=%s, pushMembers=%s}"
                .formatted(dryRun(), source(), mapPointer, defaults, pushMembers);
    }
}
