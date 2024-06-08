package org.commonhaus.automation.admin.api;

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
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.admin.api.CommonhausUser.ForwardEmail;
import org.commonhaus.automation.admin.api.CommonhausUser.Services;
import org.commonhaus.automation.admin.forwardemail.Alias;
import org.commonhaus.automation.admin.github.AppContextService;
import org.commonhaus.automation.admin.github.CommonhausDatastore;

import io.quarkus.logging.Log;
import io.quarkus.security.Authenticated;

@Path("/member/aliases")
@Authenticated
@ApplicationScoped
public class MemberAliasesResource {
    final static String ID = "email";
    @Inject
    AppContextService ctx;

    @Inject
    CommonhausDatastore datastore;

    @Inject
    MemberSession session;

    @GET
    @KnownUser
    @Produces("application/json")
    public Response getAliases(@DefaultValue("false") @QueryParam("refresh") boolean refresh) {
        try {
            CommonhausUser user = datastore.getCommonhausUser(session);
            if (!user.status().mayHaveEmail()) {
                return Response.status(Response.Status.FORBIDDEN).build();
            }
            if (!ctx.validAttestation(ID)) {
                return Response.status(Response.Status.BAD_REQUEST).build();
            }

            Services services = user.services();
            ForwardEmail forwardEmail = services.forwardEmail();

            boolean possibleMissingActive = !forwardEmail.configured && ctx.validAttestation(ID);
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
                            "Fix forward email service active flag", false);
                }
            }
            return user.toResponse()
                    .setData(ApiResponse.Type.ALIAS, aliasMap)
                    .finish();
        } catch (WebApplicationException e) {
            return e.getResponse();
        } catch (Throwable e) {
            Log.debugf("Error getting aliases for %s: %s", session.login(), e);
            if (Log.isDebugEnabled()) {
                e.printStackTrace();
            }
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @POST
    @KnownUser
    @Produces("application/json")
    public Response updateAliases(Map<String, Set<String>> aliases) {

        try {
            CommonhausUser user = datastore.getCommonhausUser(session);
            if (!user.status().mayHaveEmail()) {
                return Response.status(Response.Status.FORBIDDEN).build();
            }
            if (!ctx.validAttestation(ID)) {
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
                        "Fix forward email service active flag", false);
            }
            return user.toResponse()
                    .setData(ApiResponse.Type.ALIAS, aliasMap)
                    .finish();
        } catch (WebApplicationException e) {
            return e.getResponse();
        } catch (Throwable e) {
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
    @Path("/password")
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
            // ctx.validAttestation(ID);
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
        } catch (Throwable e) {
            Log.debugf("Error generating password for %s: %s", session.login(), e);
            if (Log.isDebugEnabled()) {
                e.printStackTrace();
            }
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    public record AliasRequest(String email) {
    }
}
