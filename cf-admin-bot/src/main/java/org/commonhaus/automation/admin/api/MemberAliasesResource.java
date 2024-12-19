package org.commonhaus.automation.admin.api;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import org.commonhaus.automation.admin.data.ApiResponse;
import org.commonhaus.automation.admin.data.CommonhausUser;
import org.commonhaus.automation.admin.data.CommonhausUserData.ForwardEmail;
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
    public Response getAliases() {
        try {
            CommonhausUser user = getUser();

            // API CALL: get alias mappings
            Map<AliasKey, Alias> aliasMap = emailService.fetchAliases(session, user);

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

            Map<AliasKey, Set<String>> sanitized = emailService.sanitizeInputAddresses(session, user, aliases);
            if (sanitized.isEmpty()) {
                Log.debugf("[%s] updateAliases: No valid email addresses to update: %s", session.login(), aliases.keySet());
                return Response.status(Response.Status.NO_CONTENT).build();
            }

            // API CALL: set/update alias mappings
            Map<AliasKey, Alias> aliasMap = emailService.postAliases(sanitized, session.name());

            if (!emailConfig.hasDefaultAlias()
                    && aliasMap.keySet().stream().anyMatch(k -> emailService.isDefaultAlias(session.login(), k))) {
                user = updateHasDefaultFlag(user);
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

    @POST
    @KnownUser
    @Path("/password")
    @Produces("application/json")
    public Response generatePassword(AliasRequest request) {
        try {
            CommonhausUser user = getUser();
            Map<AliasKey, Alias> aliasMap = emailService.fetchAliases(session, user);
            AliasKey key = AliasKey.fromCache(request.email());
            Alias alias = aliasMap.get(key);

            return emailService.generatePassword(alias)
                    ? Response.noContent().build()
                    : Response.status(Response.Status.BAD_REQUEST).build();
        } catch (WebApplicationException e) {
            return e.getResponse();
        } catch (Throwable e) {
            return ctx.toResponseWithEmail("generatePassword", "Unable to generate SMTP password for " + request.email(), e);
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

    CommonhausUser updateHasDefaultFlag(CommonhausUser user) {
        CommonhausUser result = datastore.setCommonhausUser(new UpdateEvent(user,
                (c, u) -> {
                    u.services().forwardEmail().enableDefaultAlias();
                },
                "Fix forward email service active flag",
                false,
                false));
        return result == null ? user : result;
    }

    public record AliasRequest(String email) {
    }
}
