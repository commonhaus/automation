package org.commonhaus.automation.hm;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.inject.Inject;

import org.commonhaus.automation.config.EmailNotification;
import org.commonhaus.automation.config.RepoSource;
import org.commonhaus.automation.github.context.GitHubTeamService;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.github.watchers.FileWatcher;
import org.commonhaus.automation.github.watchers.MembershipWatcher;
import org.commonhaus.automation.github.watchers.MembershipWatcher.MembershipUpdate;
import org.commonhaus.automation.github.watchers.MembershipWatcher.MembershipUpdateType;
import org.commonhaus.automation.hm.config.GroupMapping;
import org.commonhaus.automation.hm.config.ManagerBotConfig;
import org.commonhaus.automation.hm.config.OrganizationConfig.OrgDefaults;
import org.commonhaus.automation.hm.config.PushToTeams;
import org.commonhaus.automation.hm.github.AppContextService;
import org.commonhaus.automation.queue.PeriodicUpdateQueue;
import org.commonhaus.automation.queue.ScheduledService;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.logging.Log;

public abstract class GroupCoordinator extends ScheduledService {
    @Inject
    AppContextService ctx;

    @Inject
    FileWatcher fileWatcher;

    @Inject
    ManagerBotConfig mgrBotConfig;

    @Inject
    GitHubTeamService teamService;

    @Inject
    MembershipWatcher membershipEvents;

    @Inject
    PeriodicUpdateQueue updateQueue;

    interface ConfigState {
        String taskGroup();

        long installationId();

        String repoFullName();

        boolean add(RepoSource repoSource);

        boolean remove(RepoSource repoSource);

        EmailNotification emailNotifications();
    }

    protected void processMembershipUpdate(String taskGroup, MembershipUpdate update) {
    }

    protected abstract void processRepoSourceUpdate(String taskGroup, RepoSource repoSource);

    /**
     * Register a file watcher for the GroupMapping source file.
     * This allows the system to react when team membership source files change.
     */
    protected void watchRepoSource(ConfigState configState, RepoSource repoSource) {
        if (configState.add(repoSource)) {
            Log.debugf("[%s] Watching source %s for taskGroup %s", me(), repoSource,
                    configState.taskGroup());
            fileWatcher.watchFile(configState.taskGroup(), configState.installationId(),
                    repoSource.repository(), repoSource.filePath(),
                    (fileUpdate) -> {
                        Log.debugf("[%s] GroupMapping source file updated: %s", me(), fileUpdate.filePath());
                        // Queue reconciliation when source file changes
                        updateQueue.queueReconciliation(configState.taskGroup(), () -> {
                            // The specific reconcile method will be called by the concrete class
                            Log.debugf("[%s] Reconciling due to GroupMapping source change: %s", me(),
                                    fileUpdate.filePath());
                            processRepoSourceUpdate(configState.taskGroup(), repoSource);
                        });
                    });
        }
    }

    /**
     * Remove file watcher for a GroupMapping source file.
     * Call this when GroupMapping is removed or its source changes.
     */
    protected void unwatchRepoSource(ConfigState configState, RepoSource repoSource) {
        if (repoSource == null || repoSource.isEmpty()) {
            return;
        }
        if (configState.remove(repoSource)) {
            Log.debugf("[%s] Unwatching GroupMapping source %s for taskGroup %s",
                    me(), repoSource, configState.taskGroup());
            fileWatcher.unwatchFile(configState.taskGroup(), repoSource.repository(), repoSource.filePath());
        }
    }

    void processGroupMapping(ConfigState configState, GroupMapping groupMapping) {
        Log.debugf("[%s] groupMapping begin %s", me(), groupMapping.source());

        // Dry run for the group configuration and/or dry run for everything
        boolean isDryRun = groupMapping.dryRun() || ctx.isDryRun();

        ScopedQueryContext orgQc = new ScopedQueryContext(ctx,
                configState.installationId(), configState.repoFullName());

        // Find and read the source file (CONTACTS.yaml)
        // First: find the repository
        RepoSource source = groupMapping.source();
        String sourceRepoName = source.repository() == null ? configState.repoFullName() : source.repository();

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
        teamService.syncMembers(teamQc, targetTeam, expectedLogins, ignoreUsers, isDryRun, emailNotifications);

        // Register watcher for team membership changes
        membershipEvents.watchMembers(taskGroup,
                teamQc.getInstallationId(),
                MembershipUpdateType.TEAM,
                targetTeam,
                (update) -> processMembershipUpdate(taskGroup, update));
    }

    public GHOrganization.RepositoryRole toRole(String method, String rolePermission, EmailNotification notifications,
            Object errorContent) {
        GHOrganization.Permission permission = GHOrganization.Permission.TRIAGE;
        if (rolePermission != null) {
            for (GHOrganization.Permission p : GHOrganization.Permission.values()) {
                if (p.name().equalsIgnoreCase(rolePermission)) {
                    permission = p;
                }
            }
            if (!permission.name().toLowerCase().equals(rolePermission.toLowerCase())) {
                Log.warnf("[%s] %s: unknown role permission %s; using TRIAGE", me(), method, rolePermission);
                ctx.sendEmail(me(), "Unknown role permission",
                        """
                                Unknown role/permission %s in config file; using TRIAGE.

                                Please check the configuration file and correct the role/permission value.

                                %s
                                """.formatted(rolePermission, errorContent.toString()),
                        ctx.getErrorAddresses(notifications));
            }
        }
        return GHOrganization.RepositoryRole.from(permission);
    }
}
