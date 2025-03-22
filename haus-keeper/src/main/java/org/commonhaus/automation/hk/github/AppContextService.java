package org.commonhaus.automation.hk.github;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.config.EmailNotification;
import org.commonhaus.automation.github.context.BaseContextService;
import org.commonhaus.automation.github.context.GitHubTeamService;
import org.commonhaus.automation.github.context.QueryContext;
import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent.RdePriority;
import org.commonhaus.automation.github.queue.PeriodicUpdateQueue;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.github.watchers.FileWatcher;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdate;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdateType;
import org.commonhaus.automation.hk.AdminDataCache;
import org.commonhaus.automation.hk.api.MemberSession;
import org.commonhaus.automation.hk.config.AdminBotConfig;
import org.commonhaus.automation.hk.config.HausKeeperConfig;
import org.commonhaus.automation.hk.config.UserManagementConfig;
import org.commonhaus.automation.hk.config.UserManagementConfig.AttestationConfig;
import org.commonhaus.automation.hk.data.MemberStatus;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkiverse.githubapp.TokenGitHubClients;
import io.quarkus.logging.Log;

@Singleton
public class AppContextService extends BaseContextService {
    public static final String ME = "haus-keeper";

    @Inject
    FileWatcher fileEvents;

    @Inject
    TokenGitHubClients tokenClients;

    @Inject
    protected AdminBotConfig adminData;

    @Inject
    protected PeriodicUpdateQueue periodicSync;

    @Inject
    GitHubTeamService teamService;

    final AtomicReference<Optional<HausKeeperConfig>> currentConfig = new AtomicReference<>(Optional.empty());

    final Set<String> attestationIds = new HashSet<>();

    public ScopedQueryContext getScopedQueryContext(String repoFullName) {
        return installationMap.getOrgScopedQueryContext(this, repoFullName);
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
    protected void repositoryDiscovered(
            @Observes @Priority(value = RdePriority.APP_DISCOVERY) RepositoryDiscoveryEvent repoEvent) {
        DiscoveryAction action = repoEvent.action();
        long installationId = repoEvent.installationId();
        GHRepository repo = repoEvent.repository();
        String repoFullName = repoEvent.repository().getFullName();

        Log.debugf("%s/repoDiscovered: %s", ME, repoFullName);

        if (action.repository() && action.added()) {
            ScopedQueryContext qc = new ScopedQueryContext(this, installationId, repo)
                    .withExisting(repoEvent.github());

            // read org config when repository is discovered
            periodicSync.queue(ME, () -> processConfigUpdate(qc));

            fileEvents.watchFile(ME,
                    installationId, repoFullName, HausKeeperConfig.PATH,
                    (fileUpdate) -> processFileUpdate(fileUpdate));
        }
    }

    /**
     * Read organization configuration from repository.
     * Called by for file update events.
     *
     * @see #readOrgConfig(ScopedQueryContext)
     */
    protected void processFileUpdate(FileUpdate fileUpdate) {
        GitHub github = fileUpdate.github();
        GHRepository repo = fileUpdate.repository();

        if (fileUpdate.updateType() == FileUpdateType.REMOVED) {
            Log.debugf("%s/processFileUpdate: %s deleted", repo.getFullName());
            // TODO: clean up associated resources.
            // Leave the watcher, in case the file is re-added later
            // currentConfig.set(Optional.empty());
            if (repo.getFullName().equals(getDataStore())) {
                currentConfig.set(Optional.empty());
            }
            return;
        }

        ScopedQueryContext qc = new ScopedQueryContext(this, fileUpdate.installationId(), fileUpdate.repository())
                .withExisting(github);
        processConfigUpdate(qc);
    }

    protected void processConfigUpdate(ScopedQueryContext qc) {
        GHRepository repo = qc.getRepository();
        if (repo == null) {
            Log.warnf("%s/readOrgConfig: repository not set in QueryContext", ME);
            return;
        }
        GHContent content = qc.readSourceFile(repo, HausKeeperConfig.PATH);
        if (content == null || qc.hasErrors()) {
            Log.debugf("%s/processConfigUpdate: no %s in %s", ME, HausKeeperConfig.PATH, repo.getFullName());
            return;
        }
        HausKeeperConfig hkCfg = qc.readYamlContent(content, HausKeeperConfig.class);
        if (hkCfg == null || qc.hasErrors()) {
            qc.logAndSendContextErrors("%s/processConfigUpdate: unable to parse %s in %s"
                    .formatted(ME, HausKeeperConfig.PATH, repo.getFullName()));
            return;
        }
        Log.debugf("%s/processConfigUpdate: found %s in %s", ME, HausKeeperConfig.PATH, repo.getFullName());
        currentConfig.set(Optional.of(hkCfg));
        updateValidAttestations(qc, hkCfg.userManagement());
    }

    public UserManagementConfig getConfig() {
        return currentConfig.get().map(HausKeeperConfig::userManagement).orElse(UserManagementConfig.DISABLED);
    }

    public EmailNotification getAddresses() {
        return currentConfig.get().map(HausKeeperConfig::emailNotifications).orElse(EmailNotification.UNDEFINED);
    }

    public String getDataStore() {
        return adminData.datastore();
    }

    public URI getMemberHome() {
        return adminData.memberHome();
    }

    private void updateValidAttestations(ScopedQueryContext qc, UserManagementConfig userConfig) {
        if (userConfig.isDisabled()) {
            return;
        }
        GHRepository repo = qc.getRepository();
        GHContent content = qc.readSourceFile(repo, userConfig.attestations().path());
        if (content == null || qc.hasErrors()) {
            Log.debugf("%s/updateValidAttestations: no %s in %s", ME,
                    userConfig.attestations().path(), repo.getFullName());
            return;
        }
        JsonNode agreements = qc.readYamlContent(content);
        if (agreements == null || qc.hasErrors()) {
            qc.logAndSendContextErrors("[%s] updateValidAttestations: unable to parse %s from %s"
                    .formatted(ME, userConfig.attestations().path(), repo.getFullName()));
            return;
        }

        List<String> newIds = new ArrayList<>();
        JsonNode attestations = agreements.get("attestations");
        if (attestations != null && attestations.isObject()) {
            attestations.fields().forEachRemaining(entry -> newIds.add(entry.getKey()));
        }
        attestationIds.addAll(newIds);
        attestationIds.retainAll(newIds);
    }

    public boolean getValidAttestations(String id) {
        // If none are defined/found, anything goes
        return attestationIds.isEmpty() || attestationIds.contains(id);
    }

    public AttestationConfig getAttestationConfig() {
        UserManagementConfig userConfig = getConfig();
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
        UserManagementConfig userConfig = getConfig();
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
                    if (teamService.isCollaborator(qc, ghUser, repoName)) {
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
                } else if (teamService.isTeamMember(qc, ghUser, teamFullName)) {
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
        UserManagementConfig userConfig = getConfig();
        if (userConfig.isDisabled()) {
            return MemberStatus.UNKNOWN;
        }
        String status = userConfig.roleStatus().get(role);
        return MemberStatus.fromString(status);
    }

    public String getTeamForRole(String role) {
        UserManagementConfig userConfig = getConfig();
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
        teamService.addTeamMember(qc, applicant, teamFullName);
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
        if (t != null) {
            if (t instanceof WebApplicationException) {
                WebApplicationException ex = (WebApplicationException) t;
                if (ex.getResponse().getStatus() >= 500) {
                    logAndSendEmail(logId, message, t);
                }
                return ((WebApplicationException) t).getResponse();
            }
            if (t.toString().toLowerCase().contains("timeout")) { // totally cheating
                return Response.status(Response.Status.GATEWAY_TIMEOUT).build();
            }
        }
        logAndSendEmail(logId, message, t);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }
}
