package org.commonhaus.automation.hm.config;

import java.util.List;

import org.commonhaus.automation.hm.config.OrganizationConfig.OrgDefaults;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Propagate the logins from the source file (CONTACTS.yaml) to the teams in the target repository.
 * CONTACTS.yaml is read as the sourceFile in GroupMapping.
 * Each element of a group looks something like this:
 *
 * <pre>
 * # - role: Council/Officer role
 * #   login: council/officer github id
 * </pre>
 *
 * <pre>
 * # - project: Project name (match key in PROJECTS.yaml)
 * #   login: project representatives's github id
 * </pre>
 * <p>
 * This configuration defines the teams these individuals should belong to.
 *
 * @param field The field in the source file that contains the login (usually 'login')
 * @param teams List of teams to which the logins should be added (organization/teamName)
 * @param preserveUsers List of users to preserve in the teams (add if missing)
 * @param ignoreUsers List of users to ignore in the teams (do nothing / skip)
 */
@RegisterForReflection
public record PushToTeams(
        String field,
        List<String> preserveUsers,
        List<String> ignoreUsers,
        List<String> teams) {

    @Override
    public List<String> teams() {
        return teams == null ? List.of() : teams;
    }

    @Override
    public List<String> preserveUsers() {
        return preserveUsers == null ? List.of() : preserveUsers;
    }

    public List<String> preserveUsers(OrgDefaults defaults) {
        return preserveUsers == null ? defaults.preserveUsers() : preserveUsers;
    }

    @Override
    public List<String> ignoreUsers() {
        return ignoreUsers == null ? List.of() : ignoreUsers;
    }

    public List<String> ignoreUsers(OrgDefaults defaults) {
        return ignoreUsers == null ? defaults.ignoreUsers() : ignoreUsers;
    }

    @Override
    public String field() {
        return field == null ? "login" : field;
    }

    public String field(OrgDefaults defaults) {
        return field == null ? defaults.field() : field;
    }

    @Override
    public String toString() {
        return "SyncToTeams{field='%s', preserveUsers=%s, teams=%s}"
                .formatted(field, preserveUsers, teams);
    }
}
