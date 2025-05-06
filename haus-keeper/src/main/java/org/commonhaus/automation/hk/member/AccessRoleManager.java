package org.commonhaus.automation.hk.member;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import org.commonhaus.automation.github.context.GitHubQueryContext;
import org.commonhaus.automation.github.context.GitHubTeamService;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.hk.ActiveHausKeeperConfig;
import org.commonhaus.automation.hk.AdminDataCache;
import org.commonhaus.automation.hk.UserLoginVerifier.LoginChangeEvent;
import org.commonhaus.automation.hk.config.UserManagementConfig;
import org.commonhaus.automation.hk.data.CommonhausUser;
import org.commonhaus.automation.hk.data.MemberStatus;
import org.commonhaus.automation.hk.github.AppContextService;
import org.commonhaus.automation.hk.github.CommonhausDatastore;
import org.commonhaus.automation.hk.github.DatastoreEvent.UpdateEvent;
import org.commonhaus.automation.hk.github.DatastoreQueryContext;
import org.kohsuke.github.GHUser;

import io.quarkus.logging.Log;

@ApplicationScoped
public class AccessRoleManager {
    private static final String ME = "🥸-roles";

    @Inject
    ActiveHausKeeperConfig hkConfig;

    @Inject
    AppContextService ctx;

    @Inject
    CommonhausDatastore datastore;

    @Inject
    GitHubTeamService teamService;

    // Fire this event when a login change is detected
    @Inject
    Event<LoginChangeEvent> loginChangeEvent;

    public boolean userIsKnown(GitHubQueryContext initQc, MemberInfo memberInfo) {
        UserManagementConfig userConfig = ctx.getConfig();
        if (userConfig.isDisabled()) {
            return false;
        }

        Boolean result = AdminDataCache.getKnownUser(memberInfo);
        if (result != null) {
            return result;
        }

        long id = memberInfo.id();
        String login = memberInfo.login();
        Set<String> roles = memberInfo.roles();

        GHUser ghUser = initQc.getUser(login);
        if (ghUser == null) {
            if (initQc.hasErrors()) {
                initQc.logAndSendContextErrors("Error while checking for known user");
            }
            // do not cache this case; unlikely to be a valid condition
            // as the user just logged in with github
            return false;
        }

        DatastoreQueryContext dqc = ctx.getDatastoreContext();
        CommonhausUser user = datastore.getCommonhausUser(memberInfo);
        if (user != null && !user.login().equalsIgnoreCase(login)) {
            // This is an existing user (by id), but the GitHub login has changed.
            // Access and other permissions based on logins may be incorrect.
            ctx.sendEmail(ME, "GitHub user login has changed", """
                    The login for user %s has changed:

                    Old login: %s
                    New login: %s

                    GitHub user %s

                    %s
                    """.formatted(
                    id, user.login(), login, user, dqc.writeYamlValue(user)),
                    dqc.getErrorAddresses(hkConfig.getAddresses()));

            // Send event to notifiy project owners that login has changed
            loginChangeEvent.fire(new LoginChangeEvent(user.login(), Optional.of(login), user.projects()));

            // Update the user login in the datastore
            user = datastore.setCommonhausUser(new UpdateEvent(user,
                    (c, u) -> {
                        u.changeLogin(login);
                    },
                    "user login changed",
                    true, true));
        }

        result = Boolean.FALSE;
        if (!userConfig.collaboratorRoles().isEmpty()) {
            Map<String, String> collabRoles = userConfig.collaboratorRoles();
            Log.debugf("collaborators: %s", collabRoles);

            for (Entry<String, String> entry : collabRoles.entrySet()) {
                String repoName = entry.getKey();
                String role = entry.getValue();

                ScopedQueryContext qc = ctx.getScopedQueryContext(repoName);
                if (qc == null) {
                    Log.errorf("No context for %s", repoName);
                } else {
                    if (teamService.isCollaborator(qc, ghUser, repoName)) {
                        roles.add(role);
                        result = Boolean.TRUE;
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
                ScopedQueryContext qc = ctx.getScopedQueryContext(orgName);

                if (qc == null) {
                    Log.errorf("No context for %s", orgName);
                } else if (teamService.isTeamMember(qc, ghUser, teamFullName)) {
                    roles.add(role);
                    result = Boolean.TRUE;
                }
            }
        }
        if (userHasProjectRole(user)) {
            // add the contributor role if they are associated with a project
            roles.add(MemberStatus.CONTRIBUTOR.name().toLowerCase());
            result = Boolean.TRUE;
        }

        AdminDataCache.setKnownUser(memberInfo, result);
        return result;
    }

    boolean userHasProjectRole(CommonhausUser user) {
        return user != null && user.projects() != null && !user.projects().isEmpty();
    }
}
