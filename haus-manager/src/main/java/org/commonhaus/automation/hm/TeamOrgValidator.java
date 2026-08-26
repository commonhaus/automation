package org.commonhaus.automation.hm;

import java.util.List;

import org.commonhaus.automation.hm.config.OrganizationConfig;

/**
 * Checks a qualified team-name string ("org/team") against a project's declared
 * {@code githubOrganizations} list. Used by {@link ProjectManager} to validate
 * {@code collaboratorSync.sourceTeam} and {@code teamMembership[].pushMembers.*.teams}
 * entries at config-load time.
 */
public class TeamOrgValidator {

    public enum Kind {
        /** Team's org portion does not match any declared {@code githubOrganizations} entry. */
        ORG_MISMATCH,
        /** No usable org/team portion could be derived from the qualified value (blank, or empty org/team side). */
        MALFORMED
    }

    public record Violation(String qualifiedTeamName, Kind kind) {
    }

    private TeamOrgValidator() {
    }

    /**
     * Validate a single already-qualified team name against a project's declared organizations.
     *
     * @param qualifiedTeamName team name, already run through {@link OrganizationConfig#toFullTeamName}
     * @param githubOrganizations the project's declared {@code githubOrganizations} entries (bare org or GitHub URL form)
     * @return a {@link Violation} if invalid, or {@code null} if the team's org matches a declared organization
     */
    public static Violation validate(String qualifiedTeamName, List<String> githubOrganizations) {
        if (qualifiedTeamName == null || qualifiedTeamName.isBlank()) {
            return new Violation(qualifiedTeamName, Kind.MALFORMED);
        }

        int slash = qualifiedTeamName.indexOf('/');
        String org = slash < 0 ? "" : qualifiedTeamName.substring(0, slash);
        String team = slash < 0 ? "" : qualifiedTeamName.substring(slash + 1);
        if (org.isBlank() || team.isBlank()) {
            return new Violation(qualifiedTeamName, Kind.MALFORMED);
        }

        boolean matches = githubOrganizations.stream()
                .anyMatch(declared -> org.equals(OrganizationConfig.normalizeOrg(declared)));
        return matches ? null : new Violation(qualifiedTeamName, Kind.ORG_MISMATCH);
    }
}
