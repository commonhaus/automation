package org.commonhaus.automation.hk.github;

import java.net.URI;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.config.EmailNotification;
import org.commonhaus.automation.config.RepoSource;
import org.commonhaus.automation.github.context.BaseContextService;
import org.commonhaus.automation.github.context.GitHubQueryContext;
import org.commonhaus.automation.github.context.GitHubTeamService;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.hk.AdminDataCache;
import org.commonhaus.automation.hk.UserManager.ActiveHausKeeperConfig;
import org.commonhaus.automation.hk.api.MemberSession;
import org.commonhaus.automation.hk.config.AdminBotConfig;
import org.commonhaus.automation.hk.config.UserManagementConfig;
import org.commonhaus.automation.hk.data.MemberStatus;
import org.kohsuke.github.GHUser;

import io.quarkiverse.githubapp.TokenGitHubClients;
import io.quarkus.logging.Log;

@Singleton
public class AppContextService extends BaseContextService {
    public static final String ME = "haus-keeper";

    @Inject
    protected GitHubTeamService teamService;

    @Inject
    TokenGitHubClients tokenClients;

    @Inject
    protected AdminBotConfig adminData;

    @Inject
    protected ActiveHausKeeperConfig hkConfig;

    public ScopedQueryContext getScopedQueryContext(String repoFullName) {
        return installationMap.getOrgScopedQueryContext(this, repoFullName);
    }

    public DatastoreQueryContext getDatastoreContext() {
        return new DatastoreQueryContext(this, tokenClients, getDataStore());
    }

    public UserQueryContext newUserQueryContext(MemberSession memberSession) {
        return new UserQueryContext(this, memberSession);
    }

    public String getDataStore() {
        return adminData.datastore();
    }

    public URI getMemberHome() {
        return adminData.memberHome();
    }

    public UserManagementConfig getConfig() {
        return hkConfig.getConfig();
    }

    public EmailNotification getAddresses() {
        return hkConfig.getAddresses();
    }

    public RepoSource getAttestationConfig() {
        return hkConfig.getAttestationConfig();
    }

    public boolean isValidAttestation(String id) {
        return hkConfig.isValidAttestation(id);
    }

    public void forgetKnown(GHUser user) {
        AdminDataCache.KNOWN_USER.invalidate(user.getLogin());
    }

    public void forgetKnown(MemberSession session) {
        AdminDataCache.KNOWN_USER.invalidate(session.login());
    }

    public boolean userIsKnown(GitHubQueryContext initQc, String login, Set<String> roles) {
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
                return ex.getResponse();
            }
            if (t.toString().toLowerCase().contains("timeout")) { // totally cheating
                return Response.status(Response.Status.GATEWAY_TIMEOUT).build();
            }
        }
        logAndSendEmail(logId, message, t);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }
}
