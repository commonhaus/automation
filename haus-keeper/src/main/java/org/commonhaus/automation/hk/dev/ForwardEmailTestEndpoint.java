package org.commonhaus.automation.hk.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Singleton;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.hk.forwardemail.Alias.AliasDomain;
import org.commonhaus.automation.hk.forwardemail.Domain;
import org.commonhaus.automation.hk.forwardemail.GeneratePassword;

import io.quarkus.arc.profile.UnlessBuildProfile;

@Singleton
@UnlessBuildProfile("prod")
@Path("/forward-email-test/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ForwardEmailTestEndpoint {

    public static class TestAlias {
        public String id;

        public AliasDomain domain;
        public String created_at;
        public String updated_at;

        public String name;
        public String description;
        public boolean is_enabled;

        public Set<String> recipients;

        public boolean has_recipient_verification;
        public Set<String> verified_recipients;
    }

    public static class TestDomain extends Domain {
        public String id;
    }

    public static final TestDomain commonhaus;
    public static final TestDomain hibernate;
    public static final TestAlias test;

    static {
        commonhaus = new TestDomain();
        commonhaus.max_recipients_per_alias = 10;
        commonhaus.name = "commonhaus.dev";
        commonhaus.created_at = "2023-12-13T16:22:20.444Z";
        commonhaus.updated_at = "2024-06-17T10:19:03.085Z";
        commonhaus.has_catchall = true;
        commonhaus.id = "" + commonhaus.name.hashCode();

        hibernate = new TestDomain();
        hibernate.max_recipients_per_alias = 10;
        hibernate.name = "hibernate.org";
        hibernate.created_at = "2023-12-13T16:22:20.444Z";
        hibernate.updated_at = "2024-06-17T10:19:03.085Z";
        hibernate.has_catchall = true;
        hibernate.id = "" + hibernate.name.hashCode();

        test = new TestAlias();
        test.name = "test";
        test.id = "66707183881a6ff4d292baeb";
        test.description = "Test Only (delete me)";
        test.recipients = Set.of("test@example.com");
        test.is_enabled = true;
        test.domain = new AliasDomain();
        test.domain.name = commonhaus.name;
    }

    public static record ApiCall(String method, String path, Map<String, Object> params) {
    }

    List<ApiCall> methodCalls = new ArrayList<>();

    public void clear() {
        methodCalls.clear();
    }

    public List<ApiCall> getMethodCalls() {
        return methodCalls;
    }

    @GET
    @Path("/domains")
    public Set<TestDomain> getDomains() {
        methodCalls.add(new ApiCall("GET", "/domains", null));
        return Set.of(commonhaus, hibernate);
    }

    @GET
    @Path("/domains/{fqdn}/aliases")
    public Set<TestAlias> getAliases(@PathParam("fqdn") String fqdn, @QueryParam("name") String name) {
        methodCalls.add(new ApiCall("GET", "/domains/" + fqdn + "/aliases",
                name == null ? null : Map.of("name", name)));
        TestDomain domain = fqdn.equals(commonhaus.name) ? commonhaus : hibernate;
        if (name == null) {
            return Set.of(
                    ForwardEmailTestEndpoint.create("first", "First Alias", "first".hashCode() + "", domain),
                    ForwardEmailTestEndpoint.create("second", "Second Alias", "second".hashCode() + "", domain));
        }
        if ("not_found".equals(name) || "make_new".equals(name)) {
            return Set.of();
        }
        if ("error".equals(name)) {
            throw new WebApplicationException(500);
        }
        if ("test".equals(name) && domain == commonhaus) {
            return Set.of(test);
        }
        return Set.of(ForwardEmailTestEndpoint.create(name, name + " First Alias", "" + name.hashCode(), domain));
    }

    @GET
    @Path("/domains/{fqdn}/aliases/{id}")
    public TestAlias getAlias(@PathParam("fqdn") String fqdn, @PathParam("id") String id) {
        methodCalls.add(new ApiCall("GET", "/domains/" + fqdn + "/aliases/" + id, null));
        TestDomain domain = fqdn.equals(commonhaus.name) ? commonhaus : hibernate;
        if ("not_found".equals(id) || "make_new".equals(id)) {
            throw new WebApplicationException(404);
        }
        if ("error".equals(id)) {
            throw new WebApplicationException(500);
        }
        if (test.id.equals(id)) {
            return test;
        }
        return ForwardEmailTestEndpoint.create(id, "Alias By Id", id, domain);
    }

    @POST
    @Path("/domains/{fqdn}/aliases")
    public Response createAlias(@PathParam("fqdn") String fqdn, TestAlias alias) {
        methodCalls.add(new ApiCall("POST", "/domains/" + fqdn + "/aliases", Map.of("alias", alias)));
        alias.domain = new AliasDomain();
        alias.domain.name = fqdn;
        return Response.ok().entity(alias).build();
    }

    @PUT
    @Path("/domains/{fqdn}/aliases/{id}")
    public Response updateAlias(@PathParam("fqdn") String fqdn, @PathParam("id") String id, TestAlias alias) {
        methodCalls.add(new ApiCall("PUT", "/domains/" + fqdn + "/aliases/" + id, Map.of("alias", alias)));
        alias.domain = new AliasDomain();
        alias.domain.name = fqdn;
        return Response.ok().entity(alias).build();
    }

    @POST
    @Path("/domains/{fqdn}/aliases/{id}/generate-password")
    public void generatePassword(@PathParam("fqdn") String fqdn, @PathParam("id") String id, GeneratePassword instructions) {
        methodCalls.add(new ApiCall("POST", "/domains/" + fqdn + "/aliases/" + id + "/generate-password",
                Map.of("instructions", instructions)));
    }

    public static TestAlias create(String name, String description, String id, TestDomain domain) {
        TestAlias alias = new TestAlias();
        alias.name = name;
        alias.description = description;
        alias.id = id;
        alias.domain = new AliasDomain();
        alias.domain.name = domain.name;
        return alias;
    }
}
