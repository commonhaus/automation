package org.commonhaus.automation.admin.github;

import java.util.Objects;

import jakarta.inject.Inject;

import org.commonhaus.automation.admin.AdminConfig.AttestationConfig;
import org.commonhaus.automation.admin.AdminDataCache;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.event.Membership;
import io.quarkiverse.githubapp.event.Push;
import io.quarkus.logging.Log;

public class AdminGitHubEvents {

    @Inject
    AppContextService ctx;

    public void updateAttestationList(@Push GHEventPayload.Push pushEvent, GitHub github) {
        GHRepository repo = pushEvent.getRepository();
        String repoFullName = repo.getFullName();
        AttestationConfig cfg = ctx.attestationConfig();

        if (Objects.equals(repoFullName, cfg.repo())
                && pushEvent.getRef().endsWith("/main")
                && ctx.commitsContain(pushEvent, cfg.path())) {
            Log.debugf("updateAttestationList (push): %s", repo.getFullName());
            ScopedQueryContext qc = ctx.refreshScopedQueryContext(
                    github,
                    pushEvent.getRepository(),
                    pushEvent.getInstallation().getId());
            ctx.updateValidAttestations(qc);
        }
    }

    public void updateMembership(@Membership GHEventPayload.Membership membershipEvent, GitHub github) {
        AdminDataCache.KNOWN_USER.invalidate(membershipEvent.getMember().getLogin());
        ScopedQueryContext qc = ctx.refreshScopedQueryContext(
                github,
                membershipEvent.getRepository(),
                membershipEvent.getInstallation().getId());

        qc.updateTeamList(membershipEvent.getOrganization(), membershipEvent.getTeam());
    }
}
