package org.commonhaus.automation.hk.api;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.hk.data.CommonhausUser;
import org.commonhaus.automation.hk.data.CommonhausUserData.Attestation;
import org.commonhaus.automation.hk.data.MemberStatus;
import org.commonhaus.automation.hk.github.AppContextService;
import org.commonhaus.automation.hk.github.CommonhausDatastore;
import org.commonhaus.automation.hk.github.CommonhausDatastore.UpdateEvent;

import io.quarkus.logging.Log;
import io.quarkus.security.Authenticated;

@Path("/member/commonhaus/attest")
@Authenticated
@ApplicationScoped
public class MemberAttestationResource {

    @Inject
    AppContextService ctx;

    @Inject
    CommonhausDatastore datastore;

    @Inject
    MemberSession session;

    @POST
    @KnownUser
    @Produces("application/json")
    public Response updateAttestation(AttestationPost post) {
        if (!ctx.getValidAttestations(post.id())) {
            Log.errorf("updateAttestation|%s: %s is an invalid attestation id", session.login(), post.id());
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        try {
            final Set<String> roles = session.roles();
            CommonhausUser user = datastore.getCommonhausUser(session, false, true);
            // update status first so correct values are used for attestation
            user.updateMemberStatus(ctx, roles);

            Attestation newAttestation = createAttestation(user.status(), post);
            String message = "Sign attestation (%s|%s)".formatted(post.id(), post.version());

            user = datastore.setCommonhausUser(new UpdateEvent(user,
                    (c, u) -> {
                        u.updateMemberStatus(c, roles);
                        u.goodUntil().attestation().put(post.id(), newAttestation);
                    },
                    message,
                    true,
                    true));

            return user.toResponse().finish();
        } catch (Throwable e) {
            return ctx.toResponse("updateAttestation", "Unable to update attestation for " + session.login(), e);
        }
    }

    @POST
    @KnownUser
    @Path("/all")
    @Produces("application/json")
    public Response updateAttestations(List<AttestationPost> postList) {
        if (postList.stream().anyMatch(x -> !ctx.getValidAttestations(x.id()))) {
            Log.errorf("updateAttestations|%s: Request includes an invalid attestation id",
                    session.login(), postList.stream().map(AttestationPost::id).toList());
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        try {
            final Set<String> roles = session.roles();
            CommonhausUser user = datastore.getCommonhausUser(session, false, true);
            // update status first so correct values are used for attestation
            user.updateMemberStatus(ctx, roles);

            Map<String, Attestation> newAttestations = new HashMap<>();

            StringBuilder message = new StringBuilder("Sign attestations ");
            for (AttestationPost p : postList) {
                newAttestations.put(p.id(), createAttestation(user.status(), p));
                message.append("(%s|%s) ".formatted(p.id(), p.version()));
            }

            user = datastore.setCommonhausUser(new UpdateEvent(user,
                    (c, u) -> {
                        u.updateMemberStatus(c, roles);
                        u.goodUntil().attestation().putAll(newAttestations);
                    },
                    message.toString(),
                    true,
                    true));

            return user.toResponse().finish();
        } catch (Throwable e) {
            return ctx.toResponse("updateAttestations", "Unable to update attestations for " + session.login(), e);
        }
    }

    Attestation createAttestation(MemberStatus userStatus, AttestationPost post) {
        LocalDate date = LocalDate.now().plusYears(1);

        return new Attestation(
                userStatus,
                date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                post.version());
    }

    public record AttestationPost(
            @Nonnull String id,
            @Nonnull String version) {
    }
}
