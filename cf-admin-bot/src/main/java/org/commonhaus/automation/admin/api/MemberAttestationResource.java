package org.commonhaus.automation.admin.api;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.admin.api.CommonhausUser.Attestation;
import org.commonhaus.automation.admin.api.CommonhausUser.AttestationPost;
import org.commonhaus.automation.admin.api.CommonhausUser.MemberStatus;
import org.commonhaus.automation.admin.github.AppContextService;
import org.commonhaus.automation.admin.github.CommonhausDatastore;

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
        if (!ctx.validAttestation(post.id())) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        try {
            CommonhausUser user = datastore.getCommonhausUser(session, false, true);
            user.updateMemberStatus(ctx, session.roles());

            Attestation newAttestation = createAttestation(user.status(), post);
            user.goodUntil().attestation.put(post.id(), newAttestation);
            String message = "Sign attestation (%s|%s)".formatted(post.id(), post.version());

            user = datastore.setCommonhausUser(user, session.roles(), message, true);
            return user.toResponse().finish();
        } catch (Throwable e) {
            Log.errorf(e, "updateAttestation: Unable to update attestation for %s: %s", session.login(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @POST
    @KnownUser
    @Path("/all")
    @Produces("application/json")
    public Response updateAttestations(List<AttestationPost> postList) {
        if (postList.stream().anyMatch(x -> !ctx.validAttestation(x.id()))) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        try {
            CommonhausUser user = datastore.getCommonhausUser(session, false, true);
            user.updateMemberStatus(ctx, session.roles());

            Map<String, Attestation> newAttestations = new HashMap<>();

            StringBuilder message = new StringBuilder("Sign attestations ");
            for (AttestationPost p : postList) {
                newAttestations.put(p.id(), createAttestation(user.status(), p));
                message.append("(%s|%s) ".formatted(p.id(), p.version()));
            }
            user.goodUntil().attestation.putAll(newAttestations);
            user = datastore.setCommonhausUser(user, session.roles(), message.toString(), true);
            return user.toResponse().finish();
        } catch (Throwable e) {
            Log.errorf(e, "updateAttestations: Unable to update attestations for %s: %s", session.login(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    Attestation createAttestation(MemberStatus userStatus, AttestationPost post) {
        LocalDate date = LocalDate.now().plusYears(1);

        return new Attestation(
                userStatus,
                date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                post.version());
    }
}
