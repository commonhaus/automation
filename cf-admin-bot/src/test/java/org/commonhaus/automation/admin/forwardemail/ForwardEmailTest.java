package org.commonhaus.automation.admin.forwardemail;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;

import org.commonhaus.automation.admin.dev.ForwardEmailTestEndpoint;
import org.commonhaus.automation.admin.github.AppContextService;
import org.commonhaus.automation.admin.github.ContextHelper;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

@QuarkusTest
public class ForwardEmailTest {

    @RestClient
    ForwardEmailClient forwardEmailClient;

    @Inject
    AppContextService ctx;

    @Inject
    ForwardEmailService forwardEmailService;

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
        ContextHelper.setUserManagementConfig(ctx);
        // This should not throw: 404 should be handled (empty response)
        forwardEmailService.fetchAliases(
                Set.of(AliasKey.fromCache("not_found@commonhaus.dev")),
                false);
    }

    @Test
    public void testAliasError() throws Exception {
        ContextHelper.setUserManagementConfig(ctx);
        assertThrows(WebApplicationException.class, () -> {
            forwardEmailService.fetchAliases(
                    Set.of(AliasKey.fromCache("error@commonhaus.dev")),
                    false);
        });
    }

    @Test
    public void testQueryAliases() throws Exception {
        ContextHelper.setUserManagementConfig(ctx);
        Map<AliasKey, Alias> aliases = forwardEmailService.fetchAliases(
                Set.of(AliasKey.fromCache("test@commonhaus.dev")),
                false);
        assertThat(aliases).size().isEqualTo(1);
    }

    @Test
    public void testCreateAlias() throws Exception {
        ContextHelper.setUserManagementConfig(ctx);
        forwardEmailService.postAliases(
                Map.of(AliasKey.fromCache("make_new@commonhaus.dev"), Set.of("new@commonhaus.org")),
                "Test User");
    }

    @Test
    public void testUpdateAlias() throws Exception {
        ContextHelper.setUserManagementConfig(ctx);
        forwardEmailService.postAliases(
                Map.of(AliasKey.fromCache("test@commonhaus.dev"), Set.of("test@commonhaus.org")),
                "Test User");
    }

    @Test
    @Disabled
    public void testGeneratePassword() {
        GeneratePassword instructions = new GeneratePassword("test@commonhaus.org");
        forwardEmailClient.getAlias("commonhaus.dev", "66707183881a6ff4d292baeb");
        forwardEmailClient.generatePassword("commonhaus.dev", "66707183881a6ff4d292baeb", instructions);
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
