package org.commonhaus.automation.hm;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.inject.Inject;

import org.commonhaus.automation.config.EmailNotification;
import org.commonhaus.automation.config.RepoSource;
import org.commonhaus.automation.github.context.GitHubTeamService;
import org.commonhaus.automation.github.queue.PeriodicUpdateQueue;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.github.watchers.FileWatcher;
import org.commonhaus.automation.github.watchers.MembershipWatcher;
import org.commonhaus.automation.github.watchers.MembershipWatcher.MembershipUpdate;
import org.commonhaus.automation.github.watchers.MembershipWatcher.MembershipUpdateType;
import org.commonhaus.automation.hm.config.GroupMapping;
import org.commonhaus.automation.hm.config.ManagerBotConfig;
import org.commonhaus.automation.hm.config.OrganizationConfig.OrgDefaults;
import org.commonhaus.automation.hm.config.PushToTeams;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHRepository;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.logging.Log;

public abstract class GroupCoordinator {

    @Inject
    AppContextService ctx;

    @Inject
    FileWatcher fileEvents;

    @Inject
    ManagerBotConfig mgrBotConfig;

    @Inject
    GitHubTeamService teamSyncService;

    @Inject
    MembershipWatcher membershipEvents;

    @Inject
    PeriodicUpdateQueue periodicSync;

    interface ConfigState {
        String taskGroup();

        long installationId();

        String repoName();

        EmailNotification emailNotifications();
    }

    protected abstract void processMembershipUpdate(String taskGroup, MembershipUpdate update);

    void processGroupMapping(ConfigState configState, GroupMapping groupMapping) {
        Log.debugf("[%s] groupMapping begin %s", me(), groupMapping.source());

        // Dry run for the group configuration and/or dry run for everything
        boolean isDryRun = groupMapping.dryRun() || ctx.isDryRun();

        ScopedQueryContext orgQc = new ScopedQueryContext(ctx,
                configState.installationId(), configState.repoName());

        // Find and read the source file (CONTACTS.yaml)
        // First: find the repository
        RepoSource source = groupMapping.source();
        String sourceRepoName = source.repository() == null ? configState.repoName() : source.repository();

        ScopedQueryContext sourceQc = orgQc.forPublicContent(sourceRepoName);
        GHRepository sourceRepo = sourceQc == null ? null : sourceQc.getRepository(sourceRepoName);
        if (sourceQc == null || sourceRepo == null) {
            ctx.sendEmail(me(), "Unable to read source file", """
                    Unable to find repository %s (%s)

                    Group configuration %s
                    """.formatted(sourceRepoName, groupMapping),
                    orgQc.getErrorAddresses(configState.emailNotifications()));
            return;
        }

        // read the file from the repository
        GHContent content = sourceQc.readSourceFile(sourceRepo, source.filePath());
        JsonNode sourceData = content == null ? null : sourceQc.readYamlContent(content);
        if (sourceData == null) {
            ctx.sendEmail(me(), "groupMapping: source file %s could not be read", """
                    Source file %s could not be read (or parsed) from %s.

                    Group configuration %s

                    %s
                    """.formatted(source.filePath(),
                    sourceRepoName,
                    groupMapping,
                    sourceQc.bundleExceptions()),
                    orgQc.getErrorAddresses(configState.emailNotifications()));
            return;
        }

        if (!groupMapping.mapPointer().isEmpty()) {
            JsonNode target = sourceData.at(groupMapping.mapPointer());
            if (target.isMissingNode()) {
                ctx.sendEmail(me(), "Unable to read source file", """
                        mapPointer %s not found in group configuration source %s

                        Group configuration %s

                        Source file %s
                        """.formatted(groupMapping.mapPointer(),
                        groupMapping.source().filePath(),
                        groupMapping,
                        sourceData),
                        orgQc.getErrorAddresses(configState.emailNotifications()));
                return;
            }
            sourceData = target;
        }
        if (!sourceData.isObject()) {
            ctx.sendEmail(me(), "Unable to read source file", """
                    source data is not an object

                    Group configuration %s

                    Source file %s
                    """.formatted(groupMapping.mapPointer(),
                    groupMapping,
                    sourceData),
                    orgQc.getErrorAddresses(configState.emailNotifications()));
            return;
        }

        OrgDefaults defaults = groupMapping.defaults();

        // Iterate over configurations in the GroupMapping to decide
        // which teams to sync and how to sync them
        // Use the source data (e.g. CONTACTS.yaml) to identify team members
        for (var entry : groupMapping.pushMembers().entrySet()) {
            String groupName = entry.getKey();
            PushToTeams sync = entry.getValue();
            String field = sync.field(defaults);

            JsonNode sourceTeamMemberList = sourceData.get(groupName);
            if (sourceTeamMemberList != null && sourceTeamMemberList.isArray()) {
                Log.debugf("[%s] groupMapping: field %s from %s to %s", me(), field, groupName, sync.teams());

                // Populate list of expected logins with those we intend to preserve
                Set<String> expectedLogins = new HashSet<>(sync.preserveUsers(defaults));
                // Find the users listed in the source data
                for (JsonNode member : sourceTeamMemberList) {
                    String login = member.get(field).asText();
                    if (login != null && login.matches("^[a-zA-Z0-9-]+$")) {
                        expectedLogins.add(login);
                    }
                }

                for (String targetTeam : sync.teams()) {
                    try {
                        doSyncTeamMembers(configState.taskGroup(), sourceQc, targetTeam,
                                expectedLogins, sync.ignoreUsers(defaults),
                                isDryRun, configState.emailNotifications());
                    } catch (Throwable t) {
                        ctx.logAndSendEmail(me(), "Error syncing team members", t,
                                orgQc.getErrorAddresses(configState.emailNotifications()));
                    }
                }

            } else {
                Log.debugf("[%s] groupMapping: group %s not found in %s", me(), groupName, sourceData);
            }
        }
        Log.debugf("[%s] groupMapping end %s", me(), groupMapping.source());
    }

    /**
     * Synchronize team members with expected logins.
     *
     * @param targetTeam
     * @param expectedLogins
     * @param ignoreUsers
     * @param isDryRun
     */
    void doSyncTeamMembers(String taskGroup, ScopedQueryContext orgQc, String targetTeam,
            Set<String> expectedLogins, List<String> ignoreUsers,
            boolean isDryRun, EmailNotification emailNotifications) {

        // We need to find the right installation id
        // so we can read/make changes to the team
        ScopedQueryContext teamQc = orgQc.forOrganization(targetTeam, isDryRun);
        if (teamQc == null) {
            Log.warnf("[%s] doSyncTeamMembers: No installation for %s; skipping team sync", me(), targetTeam);
            return;
        }

        // Delegate to the shared utility
        teamSyncService.syncMembers(teamQc, targetTeam, expectedLogins, ignoreUsers, isDryRun, emailNotifications);

        // Register watcher for team membership changes
        membershipEvents.watchMembers(taskGroup,
                teamQc.getInstallationId(),
                MembershipUpdateType.TEAM,
                targetTeam,
                (update) -> processMembershipUpdate(taskGroup, update));
    }

    protected String me() {
        return "TeamMemberCoordinator";
    }
}
