package org.commonhaus.automation.hk.github;

import java.net.URI;
import java.util.Map.Entry;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.config.EmailNotification;
import org.commonhaus.automation.config.RepoSource;
import org.commonhaus.automation.github.context.BaseContextService;
import org.commonhaus.automation.github.context.GitHubTeamService;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.hk.ActiveHausKeeperConfig;
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
        return adminData.home().datastore();
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
