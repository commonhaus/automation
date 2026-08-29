package org.commonhaus.automation.hm;

import java.util.ArrayList;
import java.util.List;

import org.commonhaus.automation.ContextService;
import org.commonhaus.automation.hm.ProjectManager.ProjectConfigState;
import org.commonhaus.automation.hm.config.GroupMapping;
import org.commonhaus.automation.hm.config.OrganizationConfig;
import org.commonhaus.automation.hm.config.ProjectConfig;
import org.commonhaus.automation.hm.config.ProjectConfig.CollaboratorSync;
import org.commonhaus.automation.hm.config.PushToTeams;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;

/**
 * Validates a project's push-target teams and collaboratorSync.sourceTeam against its
 * declared githubOrganizations, blocks bad push-target teams, and (optionally) sends one
 * aggregated notification email. Used by {@link ProjectManager} at config-load time.
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

    /**
     * All violations found across a project's push-target teams and its collaboratorSync
     * sourceTeam, split by surface since only push-target violations are ever blockable
     * (sourceTeam is a read-only lookup and is never added to blockedTeams).
     */
    public record Result(List<Violation> pushTargetViolations, List<Violation> sourceTeamViolations) {
        public boolean isEmpty() {
            return pushTargetViolations.isEmpty() && sourceTeamViolations.isEmpty();
        }

        public List<Violation> all() {
            List<Violation> all = new ArrayList<>(pushTargetViolations);
            all.addAll(sourceTeamViolations);
            return all;
        }
    }

    @CheckedTemplate
    static class Templates {
        public static native TemplateInstance teamOrgMismatch(
                String repoFullName,
                List<String> githubOrganizations,
                List<Violation> violations,
                boolean hasOrgMismatch);
    }

    private TeamOrgValidator() {
    }

    /**
     * Validates, then always blocks bad push-target teams on {@code state} (malformed entries
     * always, org-mismatch entries only when {@code mode} is ERROR; sourceTeam is a read-only
     * lookup and is never blocked). Sends one aggregated email only when {@code sendEmail} is
     * true, so callers can throttle notifications independently of blocking.
     *
     * @param ctx used to send the notification and resolve error-address fallbacks
     * @param logId identifier for logging/email attribution (e.g. {@code ProjectManager.ME})
     * @param state the project's config state (for repo name + blockedTeams mutation)
     * @param projectConfig the project configuration to validate
     * @param homeOrg the default/home organization used to qualify unqualified team names
     * @param mode the organization's configured severity for team membership verification
     * @param dryRunAddresses addresses to notify when {@code mode} is DRY_RUN
     * @param sendEmail whether to actually send the aggregated notification this call
     */
    public static void validateAndNotify(ContextService ctx, String logId, ProjectConfigState state,
            ProjectConfig projectConfig, String homeOrg, OrganizationConfig.TeamMembershipVerification mode,
            String[] dryRunAddresses, boolean sendEmail) {
        Result result = validate(projectConfig, homeOrg, state.repoFullName());
        if (result.isEmpty()) {
            return;
        }

        for (Violation violation : result.pushTargetViolations()) {
            if (violation.kind() == Kind.MALFORMED || mode == OrganizationConfig.TeamMembershipVerification.ERROR) {
                state.addBlockedTeam(violation.qualifiedTeamName());
            }
        }

        if (!sendEmail) {
            return;
        }

        String title = "[%s] Team/organization mismatch detected".formatted(logId);
        boolean hasOrgMismatch = result.all().stream()
                .anyMatch(v -> v.kind() == Kind.ORG_MISMATCH);
        String body = Templates.teamOrgMismatch(
                state.repoFullName(),
                projectConfig.githubOrganizations(),
                result.all(),
                hasOrgMismatch).render();

        String[] addresses = mode == OrganizationConfig.TeamMembershipVerification.DRY_RUN
                ? dryRunAddresses
                : ctx.getErrorAddresses(projectConfig.emailNotifications());
        ctx.sendEmail(logId, title, body, addresses);
    }

    /**
     * Validate every push-target team and the collaboratorSync sourceTeam declared by a
     * project's config against its declared githubOrganizations, applying the home-org /
     * project-slug exemption.
     *
     * @param projectConfig the project configuration to validate
     * @param homeOrg the default/home organization used to qualify unqualified team names
     * @param repoFullName the project's full repo name (e.g. {@code "commonhaus/project-hibernate"})
     * @return a {@link Result} with any violations found (empty if none)
     */
    public static Result validate(ProjectConfig projectConfig, String homeOrg, String repoFullName) {
        List<String> githubOrganizations = projectConfig.githubOrganizations();
        List<Violation> pushTargetViolations = new ArrayList<>();

        for (GroupMapping mapping : projectConfig.teamMembership()) {
            if (mapping == null || mapping.pushMembers() == null) {
                continue;
            }
            for (PushToTeams pushToTeams : mapping.pushMembers().values()) {
                for (String teamName : pushToTeams.teams()) {
                    String qualifiedTeamName = OrganizationConfig.toFullTeamName(homeOrg, teamName);
                    Violation violation = validate(qualifiedTeamName, githubOrganizations, homeOrg, repoFullName);
                    if (violation != null) {
                        pushTargetViolations.add(violation);
                    }
                }
            }
        }

        List<Violation> sourceTeamViolations = new ArrayList<>();
        CollaboratorSync collaboratorSync = projectConfig.collaboratorSync();
        if (collaboratorSync != null && collaboratorSync.sourceTeam() != null) {
            String qualifiedSourceTeam = OrganizationConfig.toFullTeamName(homeOrg, collaboratorSync.sourceTeam());
            Violation violation = validate(qualifiedSourceTeam, githubOrganizations, homeOrg, repoFullName);
            if (violation != null) {
                sourceTeamViolations.add(violation);
            }
        }

        return new Result(pushTargetViolations, sourceTeamViolations);
    }

    /**
     * Validate a single already-qualified team name against a project's declared organizations,
     * applying the home-org / project-slug exemption.
     *
     * <p>
     * A team is exempt from {@link Kind#ORG_MISMATCH} (returns {@code null}) when:
     * <ul>
     * <li>the team reference is well-formed,</li>
     * <li>the org portion equals {@code homeOrg} (case-insensitive), and</li>
     * <li>the team-name portion contains the project slug derived from {@code repoFullName}
     * (case-insensitive substring match).</li>
     * </ul>
     * The project slug is the repo-name segment after the last {@code /}, with a leading
     * {@code "project-"} prefix stripped if present (e.g. {@code "org/project-hibernate"} → {@code "hibernate"}).
     *
     * @param qualifiedTeamName team name, already run through {@link OrganizationConfig#toFullTeamName}
     * @param githubOrganizations the project's declared {@code githubOrganizations} entries
     * @param homeOrg the home organization (e.g. {@code "commonhaus"})
     * @param repoFullName the project's full repo name (e.g. {@code "commonhaus/project-hibernate"})
     * @return a {@link Violation} if invalid, or {@code null} if the team passes validation
     */
    public static Violation validate(String qualifiedTeamName, List<String> githubOrganizations,
            String homeOrg, String repoFullName) {
        if (qualifiedTeamName == null || qualifiedTeamName.isBlank()) {
            return new Violation(qualifiedTeamName, Kind.MALFORMED);
        }

        int slash = qualifiedTeamName.indexOf('/');
        String org = slash < 0 ? "" : qualifiedTeamName.substring(0, slash);
        String team = slash < 0 ? "" : qualifiedTeamName.substring(slash + 1);
        if (org.isBlank() || team.isBlank()) {
            return new Violation(qualifiedTeamName, Kind.MALFORMED);
        }

        // Home-org / project-slug exemption: a team in the home org whose name contains
        // the project slug (derived from repoFullName) is allowed without being listed
        // in githubOrganizations.
        if (org.equalsIgnoreCase(homeOrg)) {
            String slug = projectSlug(repoFullName);
            if (!slug.isBlank() && team.toLowerCase().contains(slug.toLowerCase())) {
                return null;
            }
        }

        boolean matches = githubOrganizations.stream()
                .anyMatch(declared -> org.equals(OrganizationConfig.normalizeOrg(declared)));
        return matches ? null : new Violation(qualifiedTeamName, Kind.ORG_MISMATCH);
    }

    /**
     * Derive the project slug from a full repo name by extracting the repo-name segment
     * (after the last {@code /}) and stripping a leading {@code "project-"} prefix if present.
     */
    static String projectSlug(String repoFullName) {
        int slash = repoFullName.lastIndexOf('/');
        String repoName = slash >= 0 ? repoFullName.substring(slash + 1) : repoFullName;
        if (repoName.startsWith("project-")) {
            repoName = repoName.substring("project-".length());
        }
        return repoName;
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
