package org.commonhaus.automation.hk.forwardemail;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;

import org.commonhaus.automation.hk.AdminDataCache;
import org.commonhaus.automation.hk.dev.ForwardEmailTestEndpoint;
import org.commonhaus.automation.hk.dev.ForwardEmailTestEndpoint.TestAlias;
import org.commonhaus.automation.hk.github.HausKeeperTestBase;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

@QuarkusTest
public class ForwardEmailTest extends HausKeeperTestBase {

    @Inject
    @RestClient
    ForwardEmailClient forwardEmailClient;

    @Inject
    ForwardEmailService forwardEmailService;

    @Inject
    ForwardEmailTestEndpoint testEndpoint;

    @BeforeEach
    public void setup() {
        testEndpoint.clear();
        AdminDataCache.ALIASES.invalidateAll();
    }

    /**
     * Sanity check the mock endpoint. This is used indirectly by
     * the AppContextService. Remove one portion of the mystery if
     * other tests fail.
     */
    @Test
    @TestHTTPEndpoint(ForwardEmailTestEndpoint.class)
    public void testMockEndpoint() {
        // Sanity-check mock endpoint
        given()
                .when()
                .get("domains")
                .then()
                .statusCode(200)
                .body("size()", equalTo(2));

        given()
                .when()
                .get("domains/commonhaus.dev/aliases")
                .then()
                .statusCode(200)
                .body("size()", equalTo(2));

        given() // alias by id
                .when()
                .get("domains/commonhaus.dev/aliases/not_found")
                .then()
                .statusCode(404);

        given() // alias by id
                .when()
                .get("domains/commonhaus.dev/aliases/error")
                .then()
                .statusCode(500);

        given() // alias by id
                .when()
                .get("domains/commonhaus.dev/aliases/" + ForwardEmailTestEndpoint.test.id)
                .then()
                .log().all()
                .statusCode(200)
                .body("name", equalTo("test"));

        given() // alias by name
                .when()
                .get("domains/commonhaus.dev/aliases?name=not_found")
                .then()
                .statusCode(200)
                .body("size()", equalTo(0));

        given() // alias by name
                .when()
                .get("domains/commonhaus.dev/aliases?name=error")
                .then()
                .statusCode(500);

        given() // alias by name
                .when()
                .get("domains/commonhaus.dev/aliases?name=test")
                .then()
                .log().all()
                .statusCode(200)
                .body("size()", equalTo(1));

        given() // CREATE alias
                .when()
                .contentType(ContentType.JSON)
                .body(ForwardEmailTestEndpoint.test)
                .post("domains/commonhaus.dev/aliases")
                .then()
                .statusCode(200)
                .body("name", equalTo("test"));

        given() // UPDATE alias
                .when()
                .contentType(ContentType.JSON)
                .body(ForwardEmailTestEndpoint.test)
                .put("domains/commonhaus.dev/aliases/" + ForwardEmailTestEndpoint.test.id)
                .then()
                .statusCode(200)
                .body("name", equalTo("test"));
    }

    @Test
    public void testAliasNotExist() throws Exception {
        setUserManagementConfig();

        // This should not throw: 404 should be handled (empty response)
        forwardEmailService.fetchAliases(
                Set.of(AliasKey.fromCache("not_found@commonhaus.dev")));
        var methodCalls = testEndpoint.getMethodCalls();
        assertThat(methodCalls).size().isEqualTo(1);

        var call = methodCalls.get(0);
        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.path()).isEqualTo("/domains/commonhaus.dev/aliases");
        assertThat(call.params()).containsEntry("name", "not_found");
    }

    @Test
    public void testAliasError() throws Exception {
        setUserManagementConfig();

        assertThrows(WebApplicationException.class, () -> {
            forwardEmailService.fetchAliases(
                    Set.of(AliasKey.fromCache("error@commonhaus.dev")));
        });
        var methodCalls = testEndpoint.getMethodCalls();
        assertThat(methodCalls).size().isEqualTo(1);

        var call = methodCalls.get(0);
        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.path()).isEqualTo("/domains/commonhaus.dev/aliases");
        assertThat(call.params()).containsEntry("name", "error");
    }

    @Test
    public void testQueryAliases() throws Exception {
        setUserManagementConfig();

        Map<AliasKey, Alias> aliases = forwardEmailService.fetchAliases(
                Set.of(AliasKey.fromCache("test@commonhaus.dev")));
        assertThat(aliases).size().isEqualTo(1);

        var methodCalls = testEndpoint.getMethodCalls();
        assertThat(methodCalls).size().isEqualTo(1);

        var call = methodCalls.get(0);
        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.path()).isEqualTo("/domains/commonhaus.dev/aliases");
        assertThat(call.params()).containsEntry("name", "test");
    }

    @Test
    public void testCreateAlias() throws Exception {
        setUserManagementConfig();

        Map<AliasKey, Alias> aliases = forwardEmailService.postAliases(
                Map.of(AliasKey.fromCache("make_new@commonhaus.dev"), Set.of("new@commonhaus.org")),
                "Test User");
        assertThat(aliases).size().isEqualTo(1);

        var methodCalls = testEndpoint.getMethodCalls();
        assertThat(methodCalls).size().isEqualTo(2);

        var call = methodCalls.get(0);
        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.path()).isEqualTo("/domains/commonhaus.dev/aliases");
        assertThat(call.params()).containsEntry("name", "make_new");

        call = methodCalls.get(1);
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.path()).isEqualTo("/domains/commonhaus.dev/aliases");
        var alias = (TestAlias) call.params().get("alias");
        assertThat(alias).isNotNull();
        assertThat(alias.name).isEqualTo("make_new");
    }

    @Test
    public void testUpdateAlias() throws Exception {
        setUserManagementConfig();
        forwardEmailService.postAliases(
                Map.of(AliasKey.fromCache("test@commonhaus.dev"), Set.of("test@commonhaus.org")),
                "Test User");
        var methodCalls = testEndpoint.getMethodCalls();
        assertThat(methodCalls).size().isEqualTo(2);

        var call = methodCalls.get(0);
        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.path()).isEqualTo("/domains/commonhaus.dev/aliases");
        assertThat(call.params()).containsEntry("name", "test");

        call = methodCalls.get(1);
        assertThat(call.method()).isEqualTo("PUT");
        assertThat(call.path()).isEqualTo("/domains/commonhaus.dev/aliases/" + ForwardEmailTestEndpoint.test.id);
        var alias = (TestAlias) call.params().get("alias");
        assertThat(alias).isNotNull();
        assertThat(alias.name).isEqualTo("test");
    }

    @Test
    public void testGeneratePassword() throws Exception {
        setUserManagementConfig();
        Map<AliasKey, Alias> aliases = forwardEmailService.fetchAliases(
                Set.of(AliasKey.fromCache("test@commonhaus.dev")));

        Alias testAlias = aliases.values().iterator().next();
        testAlias.verified_recipients = Set.of("test@commonhaus.org");

        forwardEmailService.generatePassword(testAlias);

        var methodCalls = testEndpoint.getMethodCalls();
        assertThat(methodCalls).size().isEqualTo(2);
    }

    // @Test
    // public void testAddAliases() throws IOException {
    //     String data = Files.readString(Path.of("aliases.csv"));
    //     String[] lines = data.split("\n");
    //     for (String line : lines) {
    //         if (line.contains("Ready")) {
    //             String[] parts = line.split(",");
    //             Alias newAlias = new Alias();
    //             newAlias.name = parts[0].replace("@hibernate.org", "");
    //             newAlias.recipients = Set.of(parts[1]);
    //             newAlias.is_enabled = true;

    //             forwardEmailClient.createAlias("hibernate.org", newAlias);
    //         }
    //     }
    // }
}
