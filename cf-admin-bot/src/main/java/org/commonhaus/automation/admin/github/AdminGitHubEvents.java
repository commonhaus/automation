package org.commonhaus.automation.admin.github;

import java.util.Objects;

import jakarta.inject.Inject;

import org.commonhaus.automation.admin.AdminConfig.AttestationConfig;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.event.Member;
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
            AdminQueryContext qc = ctx.newAdminQueryContext(
                    github,
                    pushEvent.getRepository(),
                    pushEvent.getInstallation().getId());
            ctx.updateValidAttestations(qc);
        }
    }

    public void updateKnownUsers(@Member GHEventPayload.Member memberEvent, GitHub github) {
        Log.debugf("updateKnownUsers (member): %s", memberEvent.getMember().getLogin());
        AdminDataCache.KNOWN_USER.invalidate(memberEvent.getMember().getLogin());
    }
}
