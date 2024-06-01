package org.commonhaus.automation.admin.api;

import java.net.URI;
import java.util.ArrayList;
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
import org.commonhaus.automation.admin.api.CommonhausUser.AttestationPost;
import org.commonhaus.automation.admin.api.CommonhausUser.ForwardEmail;
import org.commonhaus.automation.admin.api.CommonhausUser.MemberStatus;
import org.commonhaus.automation.admin.api.CommonhausUser.Services;
import org.commonhaus.automation.admin.forwardemail.Alias;
import org.commonhaus.automation.admin.github.AppContextService;
import org.commonhaus.automation.admin.github.CommonhausDatastore;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.logging.Log;
import io.quarkus.oidc.UserInfo;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;

@Path("/member")
@Authenticated
@ApplicationScoped
public class MemberApi {

    @ConfigProperty(name = "quarkus.oidc.authentication.cookie-domain")
    String cookieDomain;

    @Inject
    AppContextService ctx;

    @Inject
    UserInfo userInfo;

    @Inject
    SecurityIdentity identity;

    @Inject
    CommonhausDatastore datastore;

    @GET
    @Path("/github")
    @Produces("application/json")
    public Response githubLogin() {
        // entry point: cache some member details
        MemberSession
                .getMemberProfile(ctx, userInfo, identity);

        // redirect to the member home page
        return Response.seeOther(URI.create("/member/login"))
                .build();
    }

    @GET
    @Path("/login")
    @Produces("application/json")
    public Response finishLogin(@DefaultValue("false") @QueryParam("refresh") boolean refresh) {
        // entry point: cache some member details
        MemberSession member = MemberSession
                .getMemberProfile(ctx, userInfo, identity);

        if (refresh) {
            AdminDataCache.KNOWN_USER.invalidate(member.login());
        }

        // redirect to the member home page
        return Response.seeOther(ctx.getMemberHome())
                .cookie(new NewCookie.Builder("id")
                        .value(member.nodeId)
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
        // cache/retrieve member details
        MemberSession memberProfile = MemberSession
                .getMemberProfile(ctx, userInfo, identity)
                .withRoles(ctx);

        Log.debugf("/member/me %s: hasConnection=%s, userData=%s",
                memberProfile.login(), memberProfile.hasConnection(), memberProfile.getUserData());

        return memberProfile.hasConnection()
                ? Response.ok(new MemberApiResponse(MemberApiResponse.Type.INFO, memberProfile.getUserData())).build()
                : Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }

    @GET
    @KnownUser
    @Path("/aliases")
    @Produces("application/json")
    public Response getAliases(@DefaultValue("false") @QueryParam("refresh") boolean refresh) {
        // cache/retrieve member details
        MemberSession memberProfile = MemberSession
                .getMemberProfile(ctx, userInfo, identity);

        try {
            CommonhausUser user = datastore.getCommonhausUser(memberProfile);
            Services services = user.services();
            ForwardEmail forwardEmail = services.forwardEmail();

            List<String> addresses = new ArrayList<>();
            addresses.add(memberProfile.login());
            addresses.addAll(forwardEmail.altAlias());

            boolean possibleMissingActive = !forwardEmail.active && ctx.validAttestation("email");
            boolean checkAlias = forwardEmail.active || possibleMissingActive;
            Log.debugf("Checking aliases for %s, possibleMissingActive=%s, checkAlias=%s",
                    memberProfile.login(), possibleMissingActive, checkAlias);

            Map<String, Alias> aliasMap = Map.of();
            if (checkAlias) {
                // Make request to forward email service to fetch aliases
                aliasMap = ctx.getAliases(addresses, refresh);
            }

            MemberApiResponse responseEntity = new MemberApiResponse(MemberApiResponse.Type.ALIAS, aliasMap);

            if (possibleMissingActive && !aliasMap.isEmpty()) {
                // Compensate for missed setting of active flag
                responseEntity.addAll(updateActiveFlag(memberProfile, user,
                        "Update forward email service active flag for %s".formatted(user.id())));
            }

            return aliasMap.isEmpty()
                    ? Response.status(Response.Status.NO_CONTENT).build()
                    : Response.ok(responseEntity).build();
        } catch (WebApplicationException e) {
            return e.getResponse();
        } catch (Exception e) {
            Log.debugf("Error getting aliases for %s: %s", memberProfile.login(), e);
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
        // cache/retrieve member details
        MemberSession memberProfile = MemberSession
                .getMemberProfile(ctx, userInfo, identity);

        try {
            CommonhausUser user = datastore.getCommonhausUser(memberProfile);
            ForwardEmail forwardEmail = user.services().forwardEmail();

            boolean possibleMissingActive = !forwardEmail.active && ctx.validAttestation("email");
            boolean setAlias = forwardEmail.active || possibleMissingActive;

            Map<String, Alias> aliasMap = Map.of();
            if (setAlias) {
                // Make request to forward email service to update aliases
                aliasMap = ctx.setRecipients(memberProfile.name(), aliases);
            }

            MemberApiResponse responseEntity = new MemberApiResponse(MemberApiResponse.Type.ALIAS, aliasMap);

            if (possibleMissingActive && !aliasMap.isEmpty()) {
                // Save/set active flag if aliases have been created
                responseEntity.addAll(updateActiveFlag(memberProfile, user,
                        "Update forward email service active flag for %s".formatted(user.id())));
            }

            return aliasMap.isEmpty()
                    ? Response.status(Response.Status.NO_CONTENT).build()
                    : Response.ok(responseEntity).build();
        } catch (WebApplicationException e) {
            return e.getResponse();
        } catch (Exception e) {
            Log.debugf("Error getting aliases for %s: %s", memberProfile.login(), e);
            if (Log.isDebugEnabled()) {
                e.printStackTrace();
            }
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @POST
    @KnownUser
    @Path("/aliases/password")
    @Produces("application/json")
    public Response generatePassword(AliasRequest alias) {
        // cache/retrieve member details
        MemberSession memberProfile = MemberSession
                .getMemberProfile(ctx, userInfo, identity);

        try {
            // TODO: Generate Password API is not available quite yet.. SOOOON
            // CommonhausUser user = datastore.getCommonhausUser(memberProfile);
            // ForwardEmail forwardEmail = user.services().forwardEmail();

            // boolean possibleMissingActive = !forwardEmail.active && ctx.validAttestation("email");
            // boolean generatePassword = (forwardEmail.active || possibleMissingActive);
            // if (generatePassword && !forwardEmail.validAddress(alias.email(), memberProfile.login(), ctx.getDefaultDomain())) {
            //     return Response.status(Response.Status.BAD_REQUEST).build();
            // }

            // boolean updated = generatePassword && ctx.generatePassword(alias.email());

            // MemberApiResponse responseEntity = new MemberApiResponse();

            // if (possibleMissingActive && updated) {
            //     // Save/set active flag if aliases have been created
            //     responseEntity.addAll(updateActiveFlag(memberProfile, user,
            //             "Update forward email service active flag for %s".formatted(user.id())));
            // }

            return Response.noContent().build();
        } catch (WebApplicationException e) {
            return e.getResponse();
        } catch (Exception e) {
            Log.debugf("Error generating password for %s: %s", memberProfile.login(), e);
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
    public Response getCommonhausUser() {
        // cache/retrieve member details
        MemberSession memberProfile = MemberSession
                .getMemberProfile(ctx, userInfo, identity);

        try {
            CommonhausUser user = datastore.getCommonhausUser(memberProfile);
            return Response.ok(new MemberApiResponse(MemberApiResponse.Type.HAUS, user.data)).build();
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

        MemberSession memberProfile = MemberSession
                .getMemberProfile(ctx, userInfo, identity)
                .withRoles(ctx);

        try {
            CommonhausUser user = datastore.getCommonhausUser(memberProfile);
            updateRoles(user, memberProfile.roles());

            user.appendAttestation(user.status(), post);
            String message = "%s added attestation (%s|%s)".formatted(user.id(), post.id(), post.version());
            return updateCommonhausUser(memberProfile, user, message);
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

        MemberSession memberProfile = MemberSession
                .getMemberProfile(ctx, userInfo, identity)
                .withRoles(ctx);

        try {
            CommonhausUser user = datastore.getCommonhausUser(memberProfile);
            updateRoles(user, memberProfile.roles());
            String message = user.id() + " added attestations ";
            for (AttestationPost p : postList) {
                user.appendAttestation(user.status(), p);
                message += "(" + p.id() + "|" + p.version() + ") ";
            }
            user = datastore.setCommonhausUser(user, memberProfile.roles(), message);

            return user.postConflict()
                    ? Response
                            .status(Response.Status.CONFLICT)
                            .entity(new MemberApiResponse(MemberApiResponse.Type.HAUS, user.data))
                            .build()
                    : Response.ok(new MemberApiResponse(MemberApiResponse.Type.HAUS, user.data)).build();
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
    public Response updateUserStatus(@DefaultValue("false") @QueryParam("refresh") boolean refresh) {
        // cache/retrieve member details
        MemberSession memberProfile = MemberSession
                .getMemberProfile(ctx, userInfo, identity)
                .withRoles(ctx);

        try {
            CommonhausUser user = datastore.getCommonhausUser(memberProfile, refresh);
            if (updateRoles(user, memberProfile.roles())) {
                // Refresh the user's status
                return updateCommonhausUser(memberProfile, user, "Update status");
            }
            return Response.ok(new MemberApiResponse(MemberApiResponse.Type.HAUS, user.data)).build();
        } catch (Exception e) {
            if (Log.isDebugEnabled()) {
                e.printStackTrace();
            }
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    private MemberApiResponse updateActiveFlag(MemberSession memberProfile, CommonhausUser user, String message) {
        user.services().forwardEmail().active = true;
        user = datastore.setCommonhausUser(user, memberProfile.roles(), message);

        // don't return null; will have to fix the active flag another time
        return user.postConflict()
                ? new MemberApiResponse()
                : new MemberApiResponse(MemberApiResponse.Type.HAUS, user.data);
    }

    private Response updateCommonhausUser(MemberSession memberProfile, CommonhausUser user, String message) {
        user = datastore.setCommonhausUser(user, memberProfile.roles(), message);

        return user.postConflict()
                ? Response
                        .status(Response.Status.CONFLICT)
                        .entity(new MemberApiResponse(MemberApiResponse.Type.HAUS, user.data))
                        .build()
                : Response.ok(new MemberApiResponse(MemberApiResponse.Type.HAUS, user.data)).build();
    }

    boolean updateRoles(CommonhausUser user, List<String> roles) {
        MemberStatus oldStatus = user.status();
        if (user.status() == MemberStatus.UNKNOWN && !roles.isEmpty()) {
            List<MemberStatus> status = roles.stream()
                    .map(r -> ctx.getStatusForRole(r))
                    .toList();
            user.updateStatus(status);
        }
        return oldStatus != user.status();
    }

    public static record AliasRequest(String email) {
    }
}
