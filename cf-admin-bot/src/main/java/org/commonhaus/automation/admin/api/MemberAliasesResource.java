package org.commonhaus.automation.admin.api;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import org.commonhaus.automation.admin.api.CommonhausUser.ForwardEmail;
import org.commonhaus.automation.admin.api.CommonhausUser.Services;
import org.commonhaus.automation.admin.forwardemail.Alias;
import org.commonhaus.automation.admin.forwardemail.AliasKey;
import org.commonhaus.automation.admin.forwardemail.ForwardEmailService;
import org.commonhaus.automation.admin.github.AppContextService;
import org.commonhaus.automation.admin.github.CommonhausDatastore;
import org.commonhaus.automation.admin.github.CommonhausDatastore.UpdateEvent;

import io.quarkus.logging.Log;
import io.quarkus.security.Authenticated;

@Path("/member/aliases")
@KnownUser
@Authenticated
@ApplicationScoped
public class MemberAliasesResource {
    final static String ID = "email";
    final static String UNKNOWN_USER = "Unknown user";

    @Inject
    AppContextService ctx;

    @Inject
    CommonhausDatastore datastore;

    @Inject
    MemberSession session;

    @Inject
    ForwardEmailService emailService;

    @GET
    @KnownUser
    @Produces("application/json")
    public Response getAliases(@DefaultValue("false") @QueryParam("refresh") boolean refresh) {
        try {
            CommonhausUser user = getUser();
            Services services = user.services();
            ForwardEmail emailConfig = services.forwardEmail();

            Set<AliasKey> emailAddresses = emailService.normalizeEmailAddresses(session, emailConfig);

            // API CALL: get alias mappings
            Map<AliasKey, Alias> aliasMap = emailService.fetchAliases(emailAddresses, refresh);

            // Return as map of string / alias
            return user.toResponse()
                    .setData(ApiResponse.Type.ALIAS, aliasMap.entrySet().stream()
                            .collect(Collectors.toMap(e -> e.getKey().email(), Map.Entry::getValue)))
                    .finish();
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
                return new ApiResponse(ApiResponse.Type.ALIAS, Map.of()).finish();
            }
            return e.getResponse();
        } catch (Throwable e) {
            return ctx.toResponseWithEmail("getAliases", "Unable to fetch user aliases for " + session.login(), e);
        }
    }

    @POST
    @KnownUser
    @Produces("application/json")
    public Response updateAliases(Map<String, Set<String>> aliases) {
        try {
            CommonhausUser user = getUser();
            ForwardEmail emailConfig = user.services().forwardEmail();

            Set<AliasKey> emailAddresses = emailService.normalizeEmailAddresses(session, emailConfig);
            Map<AliasKey, Set<String>> sanitized = emailService.sanitizeInputAddresses(aliases, emailAddresses);
            if (sanitized.isEmpty()) {
                Log.debugf("[%s] updateAliases: No valid email addresses to update: %s", session.login(), aliases.keySet());
                return Response.status(Response.Status.NO_CONTENT).build();
            }

            // API CALL: set/update alias mappings
            Map<AliasKey, Alias> aliasMap = emailService.postAliases(sanitized, session.name());

            if (!emailConfig.configured && !aliasMap.isEmpty()) {
                user = updateConfiguredFlag(user);
            }

            return user.toResponse()
                    .setData(ApiResponse.Type.ALIAS, aliasMap.entrySet().stream()
                            .collect(Collectors.toMap(e -> e.getKey().email(), Map.Entry::getValue)))
                    .finish();
        } catch (WebApplicationException e) {
            Log.errorf(e, "updateAliases: Unable to update user aliases for %s: %s", session.login(), e);
            return e.getResponse();
        } catch (Throwable e) {
            return ctx.toResponseWithEmail("updateAliases", "Unable to update user aliases for " + session.login(), e);
        }
    }

    protected CommonhausUser getUser() {
        CommonhausUser user = datastore.getCommonhausUser(session);
        if (user == null) {
            // should never happen
            throw new WebApplicationException(UNKNOWN_USER, Status.FORBIDDEN);
        }
        if (!user.status().mayHaveEmail()) {
            Log.infof("getAliases|%s User is not eligible for email", user.login());
            throw new WebApplicationException(Status.FORBIDDEN);
        }
        if (!ctx.validAttestation(ID)) {
            // Not the user's fault.. misconfiguration
            Exception e = new Exception("Invalid attestation id");
            ctx.logAndSendEmail("getUser", ID + " is an nvalid attestation id", e, null);
        }
        return user;
    }

    CommonhausUser updateConfiguredFlag(CommonhausUser user) {
        // eventual consistency. No big deal if this
        CommonhausUser result = datastore.setCommonhausUser(new UpdateEvent(user,
                (c, u) -> {
                    u.services().forwardEmail().configured = true;
                },
                "Fix forward email service active flag",
                false,
                false));
        return result == null ? user : result;
    }

    // @POST
    // @KnownUser
    // @Path("/password")
    // @Produces("application/json")
    // public Response generatePassword(AliasRequest alias) {
    //     try {
    //         // TODO: Generate Password API is not available quite yet.. SOOOON
    //         // CommonhausUser user = datastore.getCommonhausUser(memberSession);
    //         // if (!user.status().mayHaveEmail()) {
    //         // return Response.status(Response.Status.FORBIDDEN).build();
    //         // }

    //         // ForwardEmail forwardEmail = user.services().forwardEmail();

    //         // boolean possibleMissingActive = !forwardEmail.active &&
    //         // ctx.validAttestation(ID);
    //         // boolean generatePassword = (forwardEmail.active || possibleMissingActive);
    //         // if (generatePassword && !forwardEmail.validAddress(alias.email(),
    //         // memberSession.login(), ctx.getDefaultDomain())) {
    //         // return Response.status(Response.Status.BAD_REQUEST).build();
    //         // }

    //         // boolean updated = generatePassword && ctx.generatePassword(alias.email());

    //         // MemberApiResponse responseEntity = new MemberApiResponse();

    //         // if (possibleMissingActive && updated) {
    //         // // Save/set active flag if aliases have been created
    //         // responseEntity.addAll(updateActiveFlag(memberSession, user,
    //         // "Update forward email service active flag for %s".formatted(user.id())));
    //         // }

    //         return Response.noContent().build();
    //     } catch (WebApplicationException e) {
    //         return e.getResponse();
    //     } catch (Throwable e) {
    //         return ctx.toResponseWithEmail("generatePassword", "Unable to generate SMTP password for " + alias.email(), e);
    //     }
    // }

    public record AliasRequest(String email) {
    }
}
