package org.commonhaus.automation.admin.api;

import java.net.URI;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.admin.api.CommonhausUser.Attestation;
import org.commonhaus.automation.admin.github.AppContextService;
import org.commonhaus.automation.admin.github.CommonhausDatastore;

import io.quarkus.oidc.UserInfo;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;

@Path("/member")
@Authenticated
@ApplicationScoped
public class MemberApi {
    @Inject
    AppContextService ctx;

    @Inject
    UserInfo userInfo;

    @Inject
    SecurityIdentity identity;

    @Inject
    CommonhausDatastore datastore;

    @GET
    @KnownUser
    @Path("/me")
    @Produces("application/json")
    public Response getUserInfo() {
        // cache/retrieve member details
        MemberSession memberProfile = MemberSession
                .getMemberProfile(ctx, userInfo, identity);

        return memberProfile.hasConnection()
                ? Response.ok(new Message(Message.Type.INFO, memberProfile.getUserData())).build()
                : Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }

    @GET
    @KnownUser
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
    @KnownUser
    @Path("/login")
    @Produces("application/json")
    public Response finishLogin() {
        // entry point: cache some member details
        MemberSession member = MemberSession
                .getMemberProfile(ctx, userInfo, identity);

        // redirect to the member home page
        return Response.seeOther(ctx.getMemberHome())
                .cookie(new NewCookie.Builder("id")
                        .value(member.nodeId)
                        .path("/")
                        .secure(true)
                        .maxAge(30)
                        .build())
                .build();
    }

    @GET
    @KnownUser
    @Path("/gh-emails")
    @Produces("application/json")
    public Response getEmails() {
        // cache/retrieve member details
        MemberSession memberProfile = MemberSession
                .getMemberProfile(ctx, userInfo, identity)
                .withEmails();

        return memberProfile.hasConnection()
                ? Response.ok(new Message(Message.Type.EMAIL, memberProfile.userData.gh_emails)).build()
                : Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }

    @GET
    @KnownUser
    @Path("/commonhaus")
    @Produces("application/json")
    public Response getCommonhausUser() {
        // cache/retrieve member details
        MemberSession memberProfile = MemberSession
                .getMemberProfile(ctx, userInfo, identity);

        CommonhausUser user = datastore.getCommonhausUser(memberProfile.login(), memberProfile.id());
        return user == null
                ? Response.status(Response.Status.NOT_FOUND).build()
                : Response.ok(new Message(Message.Type.HAUS, user.data)).build();
    }

    @PUT
    @KnownUser
    @Path("/commonhaus/attest")
    @Produces("application/json")
    public Response updateCommonhausUser(Attestation attestation) {
        if (attestation.isValid(ctx)) {
            // cache/retrieve member details
            MemberSession memberProfile = MemberSession
                    .getMemberProfile(ctx, userInfo, identity);

            CommonhausUser user = datastore.getCommonhausUser(memberProfile.login(), memberProfile.id());
            if (user == null) { // not found, it's ok, make a new one in this case
                user = new CommonhausUser(memberProfile.login(), memberProfile.id());
            }
            user.append(attestation);

            user = datastore.setCommonhausUser(user,
                    "%s added attestation (%s|%s)"
                            .formatted(user.id(), attestation.id(), attestation.version()));

            return user != null
                    ? Response.ok(new Message(Message.Type.HAUS, user.data)).build()
                    : Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
        return Response.status(Response.Status.BAD_REQUEST).build();
    }
}
