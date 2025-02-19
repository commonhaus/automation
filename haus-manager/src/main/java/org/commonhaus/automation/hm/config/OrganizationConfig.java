package org.commonhaus.automation.hm.config;

import java.util.List;
import java.util.Map;

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
     * Automation to verify/synchronize/discover sponsors
     * and other financial supporters of the organization.
     *
     * @param githubSponsorable List of GitHub sponsorables to query
     * @param openCollective List of Open Collective collectives to query
     * @param targetRepository Repository to update with sponsors (as outside contributors)
     * @param atStartup If true, search for sponsors at startup
     * @param dryRun If true, do not update the repository
     */
    public record Sponsors(
            String[] githubSponsorable,
            String[] openCollective,
            String targetRepository,
            Boolean atStartup,
            Boolean dryRun) {

        @Override
        public Boolean atStartup() {
            return atStartup != null && atStartup;
        }

        @Override
        public Boolean dryRun() {
            return dryRun != null && dryRun;
        }

        @Override
        public String toString() {
            return "SponsorsConfig{dryRun=%s, targetRepository='%s', githubSponsorable='%s', openCollective=%s}"
                    .formatted(dryRun(), targetRepository, githubSponsorable, openCollective);
        }
    }

    /**
     * File to read as source of groups and their members.
     * The file should contain a list of groups, each with a list of members.
     *
     * @param sourceFile Path to the source file (usually CONTACTS.yaml)
     * @param repository Repository that contains the source file
     * @param defaults Default values for group configuration
     * @param sync Mapping of groups to teams and their members
     * @param dryRun If true, do not update team or organization membership
     */
    public record GroupMapping(
            String sourceFile,
            String repository,
            OrgDefaults defaults,
            Map<String, SyncToTeams> sync,
            Boolean dryRun) {

        public boolean performSync() {
            return sync() != null
                    && sourceFile() != null
                    && repository() != null;
        }

        @Override
        public Boolean dryRun() {
            return dryRun != null && dryRun;
        }

        @Override
        public String toString() {
            return "GroupMapping{dryRun=%s, path='%s', repo='%s', defaults=%s, sync=%s}"
                    .formatted(dryRun(), sourceFile, repository, defaults, sync);
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
     */
    public record ProjectList(
            String sourceFile,
            String repository,
            String statusField) {
        @Override
        public String statusField() {
            return statusField == null ? "status" : statusField;
        }

        @Override
        public String toString() {
            return "ProjectList{sourceFile='%s', repository=%s}"
                    .formatted(sourceFile, repository);
        }
    }
}
