package org.commonhaus.automation.hk.forwardemail;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;

import org.commonhaus.automation.hk.AdminDataCache;
import org.commonhaus.automation.hk.dev.ForwardEmailTestEndpoint;
import org.commonhaus.automation.hk.dev.ForwardEmailTestEndpoint.TestAlias;
import org.commonhaus.automation.hk.github.HausKeeperTestBase;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

@QuarkusTest
@GitHubAppTest
public class ForwardEmailTest extends HausKeeperTestBase {

    @Inject
    @RestClient
    ForwardEmailClient forwardEmailClient;

    @Inject
    ForwardEmailService forwardEmailService;

    @Inject
    ForwardEmailTestEndpoint testEndpoint;

    @Inject
    ObjectMapper objectMapper;

    @Override
    @BeforeEach
    public void init() throws Exception {
        super.init();
        testEndpoint.clear();
        AdminDataCache.ALIASES.invalidateAll();
        AdminDataCache.DOMAINS.invalidateAll();
        setupInstallationRepositories();
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

        given() // GENERATE PASSWORD
                .when()
                .contentType(ContentType.JSON)
                .body(new GeneratePassword(true, null, "new-password", null))
                .post("domains/commonhaus.dev/aliases/" + ForwardEmailTestEndpoint.test.id + "/generate-password")
                .then()
                .statusCode(200)
                .body("username", equalTo("test@commonhaus.dev"))
                .body("password", equalTo("new-password"));

        given() // GENERATE PASSWORD - not found
                .when()
                .contentType(ContentType.JSON)
                .body(new GeneratePassword(true, null, "new-password", null))
                .post("domains/commonhaus.dev/aliases/not_found/generate-password")
                .then()
                .statusCode(404);

        given() // GENERATE PASSWORD - error
                .when()
                .contentType(ContentType.JSON)
                .body(new GeneratePassword(true, null, "new-password", null))
                .post("domains/commonhaus.dev/aliases/error/generate-password")
                .then()
                .statusCode(500);
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
                Map.of(AliasKey.fromCache("make_new@commonhaus.dev"),
                        new AliasUpdate(Set.of("new@commonhaus.org"), false)),
                "Test User");
        assertThat(aliases).size().isEqualTo(1);

        var methodCalls = testEndpoint.getMethodCalls();
        assertThat(methodCalls).size().isEqualTo(3);

        var call = methodCalls.get(0);
        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.path()).isEqualTo("/domains");

        call = methodCalls.get(1);
        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.path()).isEqualTo("/domains/commonhaus.dev/aliases");
        assertThat(call.params()).containsEntry("name", "make_new");

        call = methodCalls.get(2);
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.path()).isEqualTo("/domains/commonhaus.dev/aliases");
        var alias = (TestAlias) call.params().get("alias");
        assertThat(alias).isNotNull();
        assertThat(alias.name).isEqualTo("make_new");
    }

    @Test
    public void testPostAliasesRejectsEmptyRecipientsNoImap() throws Exception {
        setUserManagementConfig();

        assertThrows(AliasValidationException.class, () -> forwardEmailService.postAliases(
                Map.of(AliasKey.fromCache("make_new@commonhaus.dev"),
                        new AliasUpdate(Set.of(), false)),
                "Test User"));

        // no ForwardEmail call should have been made
        assertThat(testEndpoint.getMethodCalls()).isEmpty();
    }

    @Test
    public void testPostAliasesRejectsExceedingMaxRecipients() throws Exception {
        setUserManagementConfig();

        Set<String> tooMany = IntStream.range(0, 11)
                .mapToObj(i -> "recipient" + i + "@example.com")
                .collect(Collectors.toSet());

        assertThrows(AliasValidationException.class, () -> forwardEmailService.postAliases(
                Map.of(AliasKey.fromCache("make_new@commonhaus.dev"),
                        new AliasUpdate(tooMany, false)),
                "Test User"));

        // no createAlias/updateAlias call should have been made
        assertThat(testEndpoint.getMethodCalls()).noneMatch(
                call -> call.method().equals("POST") || call.method().equals("PUT"));
    }

    @Test
    public void testPostAliasesBatchRejectedWhenOneAliasInvalid() throws Exception {
        setUserManagementConfig();

        Set<String> tooMany = IntStream.range(0, 11)
                .mapToObj(i -> "recipient" + i + "@example.com")
                .collect(Collectors.toSet());

        Map<AliasKey, AliasUpdate> batch = Map.of(
                AliasKey.fromCache("make_new@commonhaus.dev"), new AliasUpdate(tooMany, false),
                AliasKey.fromCache("test@commonhaus.dev"), new AliasUpdate(Set.of("valid@example.com"), false));

        assertThrows(AliasValidationException.class,
                () -> forwardEmailService.postAliases(batch, "Test User"));

        // neither alias in the batch should have been applied
        assertThat(testEndpoint.getMethodCalls()).noneMatch(
                call -> call.method().equals("POST") || call.method().equals("PUT"));
    }

    @Test
    public void testUpdateAlias() throws Exception {
        setUserManagementConfig();
        forwardEmailService.postAliases(
                Map.of(AliasKey.fromCache("test@commonhaus.dev"),
                        new AliasUpdate(Set.of("test@commonhaus.org"), true)),
                "Test User");
        var methodCalls = testEndpoint.getMethodCalls();
        assertThat(methodCalls).size().isEqualTo(3);

        var call = methodCalls.get(0);
        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.path()).isEqualTo("/domains");

        call = methodCalls.get(1);
        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.path()).isEqualTo("/domains/commonhaus.dev/aliases");
        assertThat(call.params()).containsEntry("name", "test");

        call = methodCalls.get(2);
        assertThat(call.method()).isEqualTo("PUT");
        assertThat(call.path()).isEqualTo("/domains/commonhaus.dev/aliases/" + ForwardEmailTestEndpoint.test.id);
        var alias = (TestAlias) call.params().get("alias");
        assertThat(alias).isNotNull();
        assertThat(alias.name).isEqualTo("test");
        assertThat(alias.has_imap).isTrue();
    }

    @Test
    public void testUpdateAliasDisableImap() throws Exception {
        setUserManagementConfig();
        // Fixture starts with has_imap=true; toggle it off.
        forwardEmailService.postAliases(
                Map.of(AliasKey.fromCache("test@commonhaus.dev"),
                        new AliasUpdate(Set.of("test@commonhaus.org"), false)),
                "Test User");
        var methodCalls = testEndpoint.getMethodCalls();
        assertThat(methodCalls).size().isEqualTo(3);

        var call = methodCalls.get(2);
        assertThat(call.method()).isEqualTo("PUT");
        var alias = (TestAlias) call.params().get("alias");
        assertThat(alias).isNotNull();
        assertThat(alias.has_imap).isFalse();

        // Verify has_imap=false is actually present on the wire, not omitted by
        // Alias's class-level @JsonInclude(NON_DEFAULT).
        Alias sent = new Alias();
        sent.has_imap = false;
        String json = objectMapper.writeValueAsString(sent);
        assertThat(json).contains("\"has_imap\":false");
    }

    @Test
    public void testGeneratePassword() throws Exception {
        setUserManagementConfig();
        Map<AliasKey, Alias> aliases = forwardEmailService.fetchAliases(
                Set.of(AliasKey.fromCache("test@commonhaus.dev")));

        Alias testAlias = aliases.values().iterator().next();
        testAlias.verified_recipients = Set.of("test@commonhaus.org");

        GeneratePasswordResponse response = forwardEmailService.generatePassword(
                testAlias, "new-password", "current-password", true, "test@commonhaus.org");

        assertThat(response).isNotNull();
        assertThat(response.password()).isEqualTo("new-password");

        var methodCalls = testEndpoint.getMethodCalls();
        assertThat(methodCalls).size().isEqualTo(2);

        var call = methodCalls.get(1);
        assertThat(call.method()).isEqualTo("POST");
        var instructions = (GeneratePassword) call.params().get("instructions");
        assertThat(instructions).isNotNull();
        assertThat(instructions.is_override()).isTrue();
        assertThat(instructions.emailed_instructions()).isEqualTo("test@commonhaus.org");
        assertThat(instructions.new_password()).isEqualTo("new-password");
        assertThat(instructions.password()).isEqualTo("current-password");
    }

    @Test
    public void testGeneratePasswordRequiresNewPassword() throws Exception {
        setUserManagementConfig();
        Map<AliasKey, Alias> aliases = forwardEmailService.fetchAliases(
                Set.of(AliasKey.fromCache("test@commonhaus.dev")));

        Alias testAlias = aliases.values().iterator().next();
        testAlias.verified_recipients = Set.of("test@commonhaus.org");

        var exception = assertThrows(WebApplicationException.class, () -> forwardEmailService.generatePassword(
                testAlias, null, null, false, null));

        assertThat(exception.getResponse().getStatus()).isEqualTo(400);
        assertThat(exception).hasMessage("new_password is required");
    }

    static java.util.stream.Stream<String> invalidNewPasswords() {
        return java.util.stream.Stream.of("x".repeat(129), " leading-space", "trailing-space ",
                "contains\"quote", "contains'apostrophe");
    }

    @ParameterizedTest
    @MethodSource("invalidNewPasswords")
    public void testGeneratePasswordRejectsInvalidNewPassword(String newPassword) throws Exception {
        setUserManagementConfig();
        Map<AliasKey, Alias> aliases = forwardEmailService.fetchAliases(
                Set.of(AliasKey.fromCache("test@commonhaus.dev")));

        Alias testAlias = aliases.values().iterator().next();
        testAlias.verified_recipients = Set.of("test@commonhaus.org");

        var exception = assertThrows(WebApplicationException.class, () -> forwardEmailService.generatePassword(
                testAlias, newPassword, null, false, null));

        assertThat(exception.getResponse().getStatus()).isEqualTo(400);
        assertThat(exception).hasMessage(
                "new_password must be 128 characters or fewer, have no leading or trailing whitespace, "
                        + "and contain no quotes or apostrophes");
    }

    @Test
    public void testGeneratePasswordImapOnlyEligible() throws Exception {
        setUserManagementConfig();
        Map<AliasKey, Alias> aliases = forwardEmailService.fetchAliases(
                Set.of(AliasKey.fromCache("test@commonhaus.dev")));

        Alias testAlias = aliases.values().iterator().next();
        testAlias.verified_recipients = Set.of();
        testAlias.has_imap = true;

        GeneratePasswordResponse response = forwardEmailService.generatePassword(
                testAlias, "new-password", null, false, null);

        assertThat(response).isNotNull();
    }

    @Test
    public void testGeneratePasswordIneligible() throws Exception {
        setUserManagementConfig();
        Map<AliasKey, Alias> aliases = forwardEmailService.fetchAliases(
                Set.of(AliasKey.fromCache("test@commonhaus.dev")));

        Alias testAlias = aliases.values().iterator().next();
        testAlias.verified_recipients = Set.of();
        testAlias.has_imap = false;

        GeneratePasswordResponse response = forwardEmailService.generatePassword(
                testAlias, "new-password", null, false, null);

        assertThat(response).isNull();
    }

    @Test
    public void testGetDomain() throws Exception {
        setUserManagementConfig();

        Domain domain = forwardEmailService.getDomain("commonhaus.dev");
        assertThat(domain).isNotNull();
        assertThat(domain.max_recipients_per_alias).isEqualTo(10);

        var methodCalls = testEndpoint.getMethodCalls();
        assertThat(methodCalls).size().isEqualTo(1);

        // second call should be served from cache, not hit the mock again
        Domain cached = forwardEmailService.getDomain("commonhaus.dev");
        assertThat(cached).isEqualTo(domain);
        assertThat(testEndpoint.getMethodCalls()).size().isEqualTo(1);
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
