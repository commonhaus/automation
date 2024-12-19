package org.commonhaus.automation.admin.github;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.admin.AdminConfig;
import org.commonhaus.automation.admin.AdminDataCache;
import org.commonhaus.automation.admin.api.MemberSession;
import org.commonhaus.automation.admin.config.AdminConfigFile;
import org.commonhaus.automation.admin.config.TeamManagementConfig;
import org.commonhaus.automation.admin.config.UserManagementConfig;
import org.commonhaus.automation.admin.config.UserManagementConfig.AttestationConfig;
import org.commonhaus.automation.admin.data.MemberStatus;
import org.commonhaus.automation.config.BotConfig;
import org.commonhaus.automation.github.context.BaseContextService;
import org.commonhaus.automation.github.context.QueryContext;
import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkiverse.githubapp.GitHubConfigFileProvider;
import io.quarkiverse.githubapp.TokenGitHubClients;
import io.quarkus.logging.Log;
import io.vertx.mutiny.core.eventbus.EventBus;

@Singleton
public class AppContextService extends BaseContextService {
    public static final String TEAM_SYNC_TRIGGER = "team-sync";

    @Inject
    TokenGitHubClients tokenClients;

    // This all feels ridiculous. But we need to find the right installation
    // for the access we need across multiple installations to construct
    // query contexts with the necessary permissions to modify team membership
    // or manage membership-related issues/user records.
    final Map<Long, InstallationContext> installationAccess = new ConcurrentHashMap<>();
    final Map<String, Long> writeToInstallId = new ConcurrentHashMap<>();
    final Map<String, Long> readToInstallId = new ConcurrentHashMap<>();

    UserManagementConfig userConfig = UserManagementConfig.DISABLED;

    final AdminConfig adminData;

    final Set<String> attestationIds = new HashSet<>();

    public AppContextService(BotConfig data, AdminConfig adminData,
            GitHubClientProvider gitHubClientProvider,
            GitHubConfigFileProvider configProvider, EventBus bus) {
        super(data, gitHubClientProvider, configProvider, bus);
        this.adminData = adminData;
    }

    public ScopedQueryContext refreshScopedQueryContext(@Nonnull MonitoredRepo repoCfg, GHRepository repository) {
        return new ScopedQueryContext(this, repoCfg.installationId(), repository)
                .addExisting(repoCfg);
    }

    public ScopedQueryContext refreshScopedQueryContext(long installationId, GHRepository repository) {
        return new ScopedQueryContext(this, installationId, repository);
    }

    public ScopedQueryContext refreshScopedQueryContext(long installationId, GHOrganization org, GHRepository repository) {
        return new ScopedQueryContext(this, installationId, org, repository);
    }

    public ScopedQueryContext getScopedQueryContext(String scope) {
        String orgName = QueryContext.toOrganizationName(scope);

        // try for write access first: full scope, then just the org
        Long installationId = writeToInstallId.getOrDefault(scope,
                writeToInstallId.get(orgName));
        if (installationId == null) {
            // if we didn't find that, look for read access: full scope, then just the org
            installationId = readToInstallId.getOrDefault(scope,
                    readToInstallId.get(orgName));
        }
        if (installationId == null) {
            Log.errorf("No installation found for %s", scope);
            return null;
        }
        InstallationContext access = installationAccess.get(installationId);
        return access.containsRepo(scope)
                ? new ScopedQueryContext(this, installationId, orgName, scope)
                : new ScopedQueryContext(this, installationId, orgName, null);
    }

    public DatastoreQueryContext getDatastoreContext() {
        return new DatastoreQueryContext(this, tokenClients, getDataStore());
    }

    public UserQueryContext newUserQueryContext(MemberSession memberSession) {
        return new UserQueryContext(this, memberSession);
    }

    /**
     * Event handler for repository discovery.
     * If the discovered repo matches the configured data store,
     * remember the installation id.
     */
    @Override
    protected void repositoryDiscovered(@Observes RepositoryDiscoveryEvent repoEvent) {
        super.repositoryDiscovered(repoEvent);

        DiscoveryAction action = repoEvent.action();
        long installationId = repoEvent.installationId();
        String repoFullName = repoEvent.repository().getFullName();
        String orgName = ScopedQueryContext.toOrganizationName(repoFullName);
        Optional<AdminConfigFile> repoConfig = repoEvent.getRepositoryConfig();

        if (action.added()) {
            InstallationContext access = installationAccess.computeIfAbsent(installationId,
                    k -> new InstallationContext(installationId, orgName));
            access.write.add(repoFullName);
            access.write.add(orgName);

            UserManagementConfig userConfig = UserManagementConfig.getUserManagementConfig(repoConfig.orElse(null));
            if (userConfig != null && !userConfig.isDisabled()) {
                this.userConfig = userConfig;
            }
            TeamManagementConfig teamConfig = TeamManagementConfig.getGroupManagementConfig(repoConfig.orElse(null));
            if (teamConfig != null && !teamConfig.isDisabled()) {
                teamConfig.setAccess(access);
            }

            // update indexes
            access.write.forEach(s -> writeToInstallId.put(s, access.installationId));
            access.read.forEach(s -> readToInstallId.put(s, access.installationId));
        } else if (action.removed()) {
            if (repoFullName.equals(getDataStore())) {
                userConfig = UserManagementConfig.DISABLED;
            }

            InstallationContext access = installationAccess.get(installationId);
            if (action.installation()) {
                // Installation is removed, forget all access
                access = installationAccess.remove(installationId);
                if (access != null) {
                    // repair or replace values first
                    installationAccess.values().forEach(remaining -> {
                        remaining.write.forEach(s -> writeToInstallId.put(s, remaining.installationId));
                        remaining.read.forEach(s -> readToInstallId.put(s, remaining.installationId));
                    });

                    // now clean up anything that still points to the installation being removed
                    writeToInstallId.values().removeIf(x -> x == installationId);
                    readToInstallId.values().removeIf(x -> x == installationId);
                }
            } else {
                // Just one repo. Remove write access, and read access if private
                access.write.remove(repoFullName);
                if (repoEvent.repository().isPrivate()) {
                    access.read.remove(repoFullName);
                }
            }
        }
    }

    public UserManagementConfig userManagementConfig() {
        return userConfig;
    }

    public Class<AdminConfigFile> getConfigType() {
        return AdminConfigFile.class;
    }

    public String getConfigFileName() {
        return AdminConfigFile.NAME;
    }

    public String getDataStore() {
        return adminData.datastore();
    }

    public URI getMemberHome() {
        return adminData.memberHome();
    }

    public void updateValidAttestations(ScopedQueryContext qc) {
        if (userConfig.isDisabled()) {
            return;
        }
        JsonNode agreements = qc.readYamlSourceFile(qc.getRepository(), userConfig.attestations().path());
        if (agreements != null) {
            List<String> newIds = new ArrayList<>();
            JsonNode attestations = agreements.get("attestations");
            if (attestations != null && attestations.isObject()) {
                attestations.fields().forEachRemaining(entry -> newIds.add(entry.getKey()));
            }
            attestationIds.addAll(newIds);
            attestationIds.retainAll(newIds);
        }
    }

    public boolean validAttestation(String id) {
        // If none are defined/found, anything goes
        return attestationIds.isEmpty() || attestationIds.contains(id);
    }

    public AttestationConfig attestationConfig() {
        if (userConfig.isDisabled()) {
            return null;
        }
        return userConfig.attestations();
    }

    public void forgetKnown(GHUser user) {
        AdminDataCache.KNOWN_USER.invalidate(user.getLogin());
    }

    public void forgetKnown(MemberSession session) {
        AdminDataCache.KNOWN_USER.invalidate(session.login());
    }

    public boolean userIsKnown(QueryContext initQc, String login, Set<String> roles) {
        if (userConfig.isDisabled()) {
            return false;
        }

        Boolean result = AdminDataCache.KNOWN_USER.get(login);
        if (result != null) {
            return result;
        }

        GHUser ghUser = initQc.getUser(login);
        if (ghUser == null) {
            // do not cache this case
            return false;
        }

        result = Boolean.FALSE;
        if (!userConfig.collaboratorRoles().isEmpty()) {
            Map<String, String> collabRoles = userConfig.collaboratorRoles();
            Log.debugf("collaborators: %s", collabRoles);

            for (Entry<String, String> entry : collabRoles.entrySet()) {
                String repoName = entry.getKey();
                String role = entry.getValue();

                ScopedQueryContext qc = getScopedQueryContext(repoName);
                if (qc == null) {
                    Log.errorf("No context for %s", repoName);
                } else {
                    if (qc.isCollaborator(ghUser, repoName)) {
                        roles.add(role);
                        result = or(result, Boolean.TRUE);
                    }
                }
            }
        }
        if (!userConfig.teamRoles().isEmpty()) {
            Map<String, String> teamRoles = userConfig.teamRoles();
            Log.debugf("teamRoles: %s", teamRoles);

            for (Entry<String, String> entry : teamRoles.entrySet()) {
                String teamFullName = entry.getKey();
                String role = entry.getValue();

                String orgName = ScopedQueryContext.toOrganizationName(teamFullName);
                ScopedQueryContext qc = getScopedQueryContext(orgName);

                if (qc == null) {
                    Log.errorf("No context for %s", orgName);
                } else if (qc.isTeamMember(ghUser, teamFullName)) {
                    roles.add(role);
                    result = or(result, Boolean.TRUE);
                }
            }
        }
        AdminDataCache.KNOWN_USER.put(login, result);
        return result;
    }

    Boolean or(Boolean a, Boolean b) {
        return Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b);
    }

    public MemberStatus getStatusForRole(String role) {
        if (userConfig.isDisabled()) {
            return MemberStatus.UNKNOWN;
        }
        String status = userConfig.roleStatus().get(role);
        return MemberStatus.fromString(status);
    }

    public String getTeamForRole(String role) {
        if (userConfig.isDisabled()) {
            return null;
        }
        return userConfig.teamRoles().entrySet().stream()
                .filter(entry -> entry.getValue().equals(role))
                .map(Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    /**
     * Event filter: check if the push event contains changes to the specified path
     *
     * @param pushEvent the push event
     * @param path the path to check
     * @return true if the push event contains changes to the path
     */
    public boolean commitsContain(GHEventPayload.Push pushEvent, String path) {
        return pushEvent.getCommits().stream()
                .anyMatch(commit -> commit.getAdded().contains(path)
                        || commit.getModified().contains(path));
    }

    /**
     * Add a member to a team using the correct query context
     *
     * @param applicant
     * @param teamFullName
     * @return
     */
    public boolean addTeamMember(GHUser applicant, String teamFullName) {
        Log.debugf("addTeamMember: %s to %s", applicant.getLogin(), teamFullName);

        // Use team-scoped query context for team member modifications
        ScopedQueryContext qc = getScopedQueryContext(teamFullName);
        qc.addTeamMember(applicant, teamFullName);
        if (qc.hasErrors()) {
            Throwable e = qc.bundleExceptions();
            qc.clearErrors();
            logAndSendEmail(qc.getLogId(), "Failed to add team member",
                    "Failed to add %s to team %s".formatted(applicant.getLogin(), teamFullName), e, null);
            return false;
        }
        return true;
    }

    public Response toResponse(String logId, String message, Throwable t) {
        if (t == null) {
            Log.errorf("[%s] %s; %s", logId, message, t);
        } else {
            if (t.toString().toLowerCase().contains("timeout")) { // totally cheating
                return Response.status(Response.Status.GATEWAY_TIMEOUT).build();
            }
            Log.errorf(t, "[%s] %s; %s", logId, message, t);
        }
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }

    public Response toResponseWithEmail(String logId, String message, Throwable t) {
        logAndSendEmail(logId, message, t, null);
        if (t != null && t.toString().toLowerCase().contains("timeout")) { // totally cheating
            return Response.status(Response.Status.GATEWAY_TIMEOUT).build();
        }
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }
}
