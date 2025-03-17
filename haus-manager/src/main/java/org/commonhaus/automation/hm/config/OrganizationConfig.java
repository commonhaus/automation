package org.commonhaus.automation.hm.config;

import java.util.List;
import java.util.Map;

import org.commonhaus.automation.config.EmailNotification;
import org.commonhaus.automation.config.RepoSource;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class OrganizationConfig {
    public static final String NAME = "cf-haus-organization.yml";
    public static final String PATH = ".github/" + NAME;

    protected EmailNotification emailNotifications;
    protected Sponsors sponsors;
    protected GroupMapping teamMembership;
    protected ProjectList projects;

    /**
     * @return the emailNotifications
     */
    public EmailNotification emailNotifications() {
        return emailNotifications == null
                ? EmailNotification.UNDEFINED
                : emailNotifications;
    }

    /**
     * @return the sponsors
     */
    public Sponsors sponsors() {
        return sponsors;
    }

    /**
     * @return the teamMembership
     */
    public GroupMapping teamMembership() {
        return teamMembership;
    }

    /**
     * @return the projects
     */
    public ProjectList projects() {
        return projects;
    }

    @Override
    public String toString() {
        return "OrganizationConfig{emailNotifications=%s, sponsors=%s, teamMembership=%s, projects=%s}"
                .formatted(emailNotifications, sponsors, teamMembership, projects);
    }

    /**
     * Automation to verify/synchronize/discover sponsors
     * and other financial supporters of the organization.
     *
     * @param sources List of sources to check for sponsors
     * @param targetRepository Repository to update with sponsors (as outside contributors)
     * @param dryRun If true, do not update the repository
     */
    public record Sponsors(
            List<RepoSource> sources,
            String targetRepository,
            Boolean dryRun) {

        @Override
        public Boolean dryRun() {
            return dryRun != null && dryRun;
        }

        @Override
        public String toString() {
            return "SponsorsConfig{dryRun=%s, targetRepository='%s', sources=%s}"
                    .formatted(dryRun(), targetRepository, sources);
        }
    }

    /**
     * File to read as source of groups and their members.
     * The file should contain a list of groups, each with a list of members.
     *
     * @param source Path to the source file (usually CONTACTS.yaml)
     * @param defaults Common fallback values for team configuration
     * @param sync Mapping of groups to teams and their members
     * @param dryRun If true, do not update team or organization membership
     */
    public record GroupMapping(
            RepoSource source,
            OrgDefaults defaults,
            Map<String, SyncToTeams> sync,
            Boolean dryRun) {

        public boolean performSync() {
            return sync() != null && source() != null && !source().isEmpty();
        }

        @Override
        public Boolean dryRun() {
            return dryRun != null && dryRun;
        }

        @Override
        public String toString() {
            return "GroupMapping{dryRun=%s, source='%s', defaults=%s, sync=%s}"
                    .formatted(dryRun(), source(), defaults, sync);
        }
    }

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
     *
     * This configuration defines the teams these individuals should belong to.
     *
     * @param field The field in the source file that contains the login (usually 'login')
     * @param teams List of teams to which the logins should be added (organization/teamName)
     * @param preserveUsers List of users to preserve in the teams (add if missing)
     * @param ignoreUsers List of users to ignore in the teams (do nothing / skip)
     */
    public record SyncToTeams(
            String field,
            List<String> teams,
            List<String> preserveUsers,
            List<String> ignoreUsers) {
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
            return ignoreUsers == null ? List.of() : preserveUsers;
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

    /**
     * Default/fallback values for team configuration.
     *
     * @param field The field in the source file that contains the login (usually 'login')
     * @param preserveUsers List of users to preserve in the teams (add if missing)
     * @param ignoreUsers List of users to ignore in the teams (do nothing / skip)
     */
    public record OrgDefaults(
            String field,
            List<String> preserveUsers,
            List<String> ignoreUsers) {
        @Override
        public String field() {
            return field == null ? "login" : field;
        }

        @Override
        public List<String> preserveUsers() {
            return preserveUsers == null ? List.of() : preserveUsers;
        }

        @Override
        public List<String> ignoreUsers() {
            return ignoreUsers == null ? List.of() : preserveUsers;
        }

        @Override
        public String toString() {
            return "OrgDefaults{field='%s', preserveUsers=%s}"
                    .formatted(field, preserveUsers);
        }
    }

    /**
     * List of projects to be verified by automation.
     *
     * @param source Repository and path of the source file (usually PROJECTS.yaml)
     * @param statusField Field in the source file that contains the status (default: 'status')
     */
    public record ProjectList(
            RepoSource source,
            String statusField) {
        @Override
        public String statusField() {
            return statusField == null ? "status" : statusField;
        }

        @Override
        public String toString() {
            return "ProjectList{source='%s'}".formatted(source);
        }
    }
}
