package org.commonhaus.automation.hk.dev;

import java.util.HashSet;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.github.context.GitHubTeamService;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.hk.github.AppContextService;
import org.kohsuke.github.GHUser;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.logging.Log;

@IfBuildProfile("dev")
@Path("/debug")
public class DebugRoutes {

    @Inject
    GitHubTeamService teamService;

    @Inject
    AppContextService appCtx;

    @GET
    @Path("/{login}/roles")
    public Response getRoles(@PathParam("login") String login) {
        Log.infof("DEBUG: getRoles(%s)", login);

        ScopedQueryContext qc = appCtx.getScopedQueryContext("commonhaus/sponsors");

        GHUser user = qc.getUser(login);
        isTeamMember(qc, user, "commonhaus/cf-council");
        isTeamMember(qc, user, "commonhaus/cf-egc");
        isTeamMember(qc, user, "commonhaus/members");
        isCollaborator(qc, user, "commonhaus/sponsors");

        Set<String> roles = new HashSet<>();
        appCtx.userIsKnown(qc, login, roles);

        // appCtx.userIsKnown(qc, login, roles);
        return Response.ok().build();
    }

    private boolean isTeamMember(ScopedQueryContext qc, GHUser user, String fullTeamName) {
        boolean result = teamService.isTeamMember(qc, user, fullTeamName);
        Log.debugf("%s isTeamMember of %s -> %s",
                user.getLogin(), fullTeamName, result);
        return result;
    }

    private boolean isCollaborator(ScopedQueryContext qc, GHUser user, String repoName) {
        boolean result = teamService.isCollaborator(qc, user, repoName);
        Log.debugf("%s isCollaborator of %s -> %s",
                user.getLogin(), repoName, result);
        return result;
    }
}
