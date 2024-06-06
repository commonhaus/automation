package org.commonhaus.automation.admin.api;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.admin.AdminDataCache;
import org.commonhaus.automation.admin.api.ApplicationData.ApplicationPost;
import org.commonhaus.automation.admin.api.CommonhausUser.Attestation;
import org.commonhaus.automation.admin.api.CommonhausUser.AttestationPost;
import org.commonhaus.automation.admin.api.CommonhausUser.ForwardEmail;
import org.commonhaus.automation.admin.api.CommonhausUser.MemberStatus;
import org.commonhaus.automation.admin.api.CommonhausUser.Services;
import org.commonhaus.automation.admin.api.MemberApiResponse.Type;
import org.commonhaus.automation.admin.forwardemail.Alias;
import org.commonhaus.automation.admin.github.AppContextService;
import org.commonhaus.automation.admin.github.CommonhausDatastore;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.logging.Log;
import io.quarkus.security.Authenticated;

@Path("/member")
@Authenticated
@ApplicationScoped
public class MemberApi {

    @ConfigProperty(name = "quarkus.oidc.authentication.cookie-domain")
    String cookieDomain;

    @Inject
    AppContextService ctx;

    @Inject
    CommonhausDatastore datastore;

    @Inject
    MemberSession session;

    @GET
    @Path("/github")
    @Produces("application/json")
    public Response githubLogin() {
        // redirect to the member home page
        return Response.seeOther(URI.create("/member/login"))
                .build();
    }

    @GET
    @Path("/login")
    @Produces("application/json")
    public Response finishLogin(@DefaultValue("false") @QueryParam("refresh") boolean refresh) {
        Log.debugf("/member/login %s: hasConnection=%s, userData=%s",
                session.login(), session.hasConnection(), session.getUserData());
        if (refresh) {
            AdminDataCache.KNOWN_USER.invalidate(session.login());
        }

        // redirect to the member home page
        return Response.seeOther(ctx.getMemberHome())
                .cookie(new NewCookie.Builder("id")
                        .value(session.nodeId())
                        .domain(cookieDomain)
                        .path("/")
                        .secure(true)
                        .maxAge(60)
                        .build())
                .build();
    }

    @GET
    @KnownUser
    @Path("/me")
    @Produces("application/json")
    public Response getUserInfo() {

        Log.debugf("/member/me %s: hasConnection=%s, userData=%s",
                session.login(), session.hasConnection(), session.getUserData());

        return session.hasConnection()
                ? Response.ok(new MemberApiResponse(MemberApiResponse.Type.INFO, session.getUserData())).build()
                : Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }

    @GET
    @KnownUser
    @Path("/aliases")
    @Produces("application/json")
    public Response getAliases(@DefaultValue("false") @QueryParam("refresh") boolean refresh) {
        try {
            CommonhausUser user = datastore.getCommonhausUser(session);
            if (!user.status().mayHaveEmail()) {
                return Response.status(Response.Status.FORBIDDEN).build();
            }
            if (!ctx.validAttestation("email")) {
                return Response.status(Response.Status.BAD_REQUEST).build();
            }

            Services services = user.services();
            ForwardEmail forwardEmail = services.forwardEmail();

            boolean possibleMissingActive = !forwardEmail.configured && ctx.validAttestation("email");
            boolean checkAlias = forwardEmail.configured || possibleMissingActive;

            Map<String, Alias> aliasMap = Map.of();
            if (checkAlias) {
                // get email addresses
                List<String> emailAddresses = getEmailAddresses(session, forwardEmail);
                // get alias mappings
                aliasMap = ctx.getAliases(emailAddresses, refresh);

                if (!forwardEmail.configured && !aliasMap.isEmpty()) {
                    forwardEmail.configured = true;
                    user = datastore.setCommonhausUser(user, session.roles(),
                            "Fix forward email service active flag");
                }
            }
            return userToResponse(user)
                    .setData(MemberApiResponse.Type.ALIAS, aliasMap)
                    .finish();
        } catch (WebApplicationException e) {
            return e.getResponse();
        } catch (Exception e) {
            Log.debugf("Error getting aliases for %s: %s", session.login(), e);
            if (Log.isDebugEnabled()) {
                e.printStackTrace();
            }
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @POST
    @KnownUser
    @Path("/aliases")
    @Produces("application/json")
    public Response updateAliases(Map<String, Set<String>> aliases) {
        try {
            CommonhausUser user = datastore.getCommonhausUser(session);
            if (!user.status().mayHaveEmail()) {
                return Response.status(Response.Status.FORBIDDEN).build();
            }
            if (!ctx.validAttestation("email")) {
                return Response.status(Response.Status.BAD_REQUEST).build();
            }

            ForwardEmail forwardEmail = user.services().forwardEmail();
            List<String> emailAddresses = getEmailAddresses(session, forwardEmail);

            // Filter/Remove any unknown/extraneous email addresses
            aliases.entrySet().removeIf(e -> !emailAddresses.contains(e.getKey()) || e.getValue().isEmpty());
            if (aliases.isEmpty()) {
                return Response.status(Response.Status.NO_CONTENT).build();
            }

            // Update alias mappings
            Map<String, Alias> aliasMap = ctx.setRecipients(session.name(), aliases);
            if (!forwardEmail.configured && !aliasMap.isEmpty()) {
                forwardEmail.configured = true;
                user = datastore.setCommonhausUser(user, session.roles(),
                        "Fix forward email service active flag");
            }
            return userToResponse(user)
                    .setData(MemberApiResponse.Type.ALIAS, aliasMap)
                    .finish();
        } catch (WebApplicationException e) {
            return e.getResponse();
        } catch (Exception e) {
            Log.debugf("Error getting aliases for %s: %s", session.login(), e);
            if (Log.isDebugEnabled()) {
                e.printStackTrace();
            }
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    List<String> getEmailAddresses(MemberSession session, ForwardEmail forwardEmail) {
        List<String> addresses = new ArrayList<>();
        addresses.add(session.login());
        addresses.addAll(forwardEmail.altAlias());
        return addresses;
    }

    @POST
    @KnownUser
    @Path("/aliases/password")
    @Produces("application/json")
    public Response generatePassword(AliasRequest alias) {

        try {
            // TODO: Generate Password API is not available quite yet.. SOOOON
            // CommonhausUser user = datastore.getCommonhausUser(memberSession);
            // if (!user.status().mayHaveEmail()) {
            // return Response.status(Response.Status.FORBIDDEN).build();
            // }

            // ForwardEmail forwardEmail = user.services().forwardEmail();

            // boolean possibleMissingActive = !forwardEmail.active &&
            // ctx.validAttestation("email");
            // boolean generatePassword = (forwardEmail.active || possibleMissingActive);
            // if (generatePassword && !forwardEmail.validAddress(alias.email(),
            // memberSession.login(), ctx.getDefaultDomain())) {
            // return Response.status(Response.Status.BAD_REQUEST).build();
            // }

            // boolean updated = generatePassword && ctx.generatePassword(alias.email());

            // MemberApiResponse responseEntity = new MemberApiResponse();

            // if (possibleMissingActive && updated) {
            // // Save/set active flag if aliases have been created
            // responseEntity.addAll(updateActiveFlag(memberSession, user,
            // "Update forward email service active flag for %s".formatted(user.id())));
            // }

            return Response.noContent().build();
        } catch (WebApplicationException e) {
            return e.getResponse();
        } catch (Exception e) {
            Log.debugf("Error generating password for %s: %s", session.login(), e);
            if (Log.isDebugEnabled()) {
                e.printStackTrace();
            }
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GET
    @KnownUser
    @Path("/commonhaus")
    @Produces("application/json")
    public Response getCommonhausUser(@DefaultValue("false") @QueryParam("refresh") boolean refresh) {
        try {
            CommonhausUser user = datastore.getCommonhausUser(session, refresh);
            return userToResponse(user).finish();
        } catch (Exception e) {
            if (Log.isDebugEnabled()) {
                e.printStackTrace();
            }
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @POST
    @KnownUser
    @Path("/commonhaus/attest")
    @Produces("application/json")
    public Response updateAttestations(AttestationPost post) {
        if (!ctx.validAttestation(post.id())) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        try {
            CommonhausUser user = datastore.getCommonhausUser(session);
            updateMemberStatus(user, session.roles());

            Attestation newAttestation = createAttestation(user.status(), post);
            user.goodUntil().attestation.put(post.id(), newAttestation);
            String message = "%s added attestation (%s|%s)".formatted(user.id(), post.id(), post.version());

            user = datastore.setCommonhausUser(user, session.roles(), message);
            return userToResponse(user).finish();
        } catch (Exception e) {
            if (Log.isDebugEnabled()) {
                e.printStackTrace();
            }
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @POST
    @KnownUser
    @Path("/commonhaus/attest/all")
    @Produces("application/json")
    public Response updateAttestations(List<AttestationPost> postList) {
        if (postList.stream().anyMatch(x -> !ctx.validAttestation(x.id()))) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        try {
            CommonhausUser user = datastore.getCommonhausUser(session);
            updateMemberStatus(user, session.roles());

            Map<String, Attestation> newAttestations = new HashMap<>();

            StringBuilder message = new StringBuilder(user.id() + " added attestations ");
            for (AttestationPost p : postList) {
                newAttestations.put(p.id(), createAttestation(user.status(), p));
                message.append("(%s|%s) ".formatted(p.id(), p.version()));
            }
            user.goodUntil().attestation.putAll(newAttestations);
            user = datastore.setCommonhausUser(user, session.roles(), message.toString());
            return userToResponse(user).finish();
        } catch (Exception e) {
            if (Log.isDebugEnabled()) {
                e.printStackTrace();
            }
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @POST
    @KnownUser
    @Path("/commonhaus/status")
    @Produces("application/json")
    public Response updateUserStatus() {
        try {
            CommonhausUser user = datastore.getCommonhausUser(session, false);
            if (updateMemberStatus(user, session.roles())) {
                // Refresh the user's status
                user = datastore.setCommonhausUser(user, session.roles(), "Update membership status");
            }
            return userToResponse(user).finish();
        } catch (Exception e) {
            if (Log.isDebugEnabled()) {
                e.printStackTrace();
            }
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GET
    @KnownUser
    @Path("/apply")
    @Produces("application/json")
    public Response getApplication() {
        try {
            CommonhausUser user = datastore.getCommonhausUser(session, false);
            ApplicationData applicationData = ctx.getOpenApplication(session, user.data.applicationId);
            if (applicationData == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            if (applicationData.notOwner()) {
                return Response.status(Response.Status.FORBIDDEN).build();
            }
            return userToResponse(user)
                    .setData(Type.APPLY, applicationData)
                    .finish();
        } catch (Exception e) {
            if (Log.isDebugEnabled()) {
                e.printStackTrace();
            }
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @POST
    @KnownUser
    @Path("/apply")
    @Produces("application/json")
    public Response setApplication(ApplicationPost applicationPost) {

        try {
            CommonhausUser user = datastore.getCommonhausUser(session, false);
            ApplicationData applicationData = ctx.updateApplication(session, user.data.applicationId, applicationPost);
            if (applicationData == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            if (applicationData.notOwner()) {
                return Response.status(Response.Status.FORBIDDEN).build();
            }

            user.data.applicationId = applicationData.issueId;
            user = datastore.setCommonhausUser(user, session.roles(), "Created membership application");

            if (user.postConflict()) { // on conflict, user is reset with value from repo
                // retry once.
                user.data.applicationId = applicationData.issueId;
                user = datastore.setCommonhausUser(user, session.roles(), "Created membership application");
            }

            return userToResponse(user)
                    .setData(Type.APPLY, applicationData)
                    .finish();
        } catch (Exception e) {
            if (Log.isDebugEnabled()) {
                e.printStackTrace();
            }
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    private MemberApiResponse userToResponse(CommonhausUser user) {
        return new MemberApiResponse(MemberApiResponse.Type.HAUS, user.data)
                .responseStatus(user.postConflict() ? Response.Status.CONFLICT : Response.Status.OK);
    }

    boolean updateMemberStatus(CommonhausUser user, Set<String> roles) {
        MemberStatus oldStatus = user.status();
        if (user.status() == MemberStatus.UNKNOWN && !roles.isEmpty()) {
            List<MemberStatus> status = roles.stream()
                    .map(r -> ctx.getStatusForRole(r))
                    .sorted()
                    .toList();
            user.data.status = status.get(0);
        }
        return oldStatus != user.status();
    }

    public Attestation createAttestation(MemberStatus userStatus, AttestationPost post) {
        LocalDate date = LocalDate.now().plusYears(1);

        return new Attestation(
                userStatus,
                date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                post.version());
    }

    public record AliasRequest(String email) {
    }
}
