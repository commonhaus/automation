package org.commonhaus.automation.hk.api;

import static io.restassured.RestAssured.given;
import static io.restassured.matcher.RestAssuredMatchers.detailedCookie;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.commonhaus.automation.github.context.TestFileNotFoundException;
import org.commonhaus.automation.hk.api.MemberAttestationResource.AttestationPost;
import org.commonhaus.automation.hk.data.CommonhausUser;
import org.commonhaus.automation.hk.data.CommonhausUserData.Attestation;
import org.commonhaus.automation.hk.data.MemberStatus;
import org.commonhaus.automation.hk.github.CommonhausDatastore;
import org.commonhaus.automation.hk.github.HausKeeperTestBase;
import org.commonhaus.automation.hk.member.MemberApplicationProcess;
import org.commonhaus.automation.hk.member.MemberApplicationProcess.ApplicationPost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHContentBuilder;
import org.kohsuke.github.GHUser;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.quarkus.test.security.oidc.UserInfo;
import io.restassured.http.ContentType;

@QuarkusTest
@GitHubAppTest
@TestHTTPEndpoint(MemberResource.class)
public class MemberDataTest extends HausKeeperTestBase {
    @Inject
    ObjectMapper mapper;

    @Inject
    CommonhausDatastore datastore;

    @Inject
    MemberApplicationProcess applicationProcess;

    boolean errorMailExpected = false;

    @Override
    @BeforeEach
    protected void init() throws Exception {
        super.init();
        setupInstallationRepositories();
        setupBotLogin();
        setupMockTeam();
        setUserManagementConfig();
    }

    @AfterEach
    void noErrorMail() {
        if (!errorMailExpected) {
            assertNoErrorEmails();
        }
    }

    @Test
    @TestSecurity(user = botLogin)
    @OidcSecurity(userinfo = {
            @UserInfo(key = "login", value = botLogin),
            @UserInfo(key = "id", value = botId + ""),
            @UserInfo(key = "node_id", value = botNodeId),
            @UserInfo(key = "avatar_url", value = "https://avatars.githubusercontent.com/u/156364140?v=4")
    })
    void testUnknownUser() throws Exception {
        setUserAsUnknown(botLogin);

        // Simple retrieval of UserInfo data (provided above)
        // If the user is unknown (not in a configured org or a contributor to specified
        // repo), we should return a 403
        given()
                .log().all()
                .when().get("/me")
                .then()
                .log().all()
                .statusCode(403);
    }

    @Test
    @TestSecurity(user = botLogin)
    @OidcSecurity(userinfo = {
            @UserInfo(key = "login", value = botLogin),
            @UserInfo(key = "id", value = botId + ""),
            @UserInfo(key = "node_id", value = botNodeId),
            @UserInfo(key = "avatar_url", value = "https://avatars.githubusercontent.com/u/156364140?v=4")
    })
    void testUserInfoEndpoint() throws Exception {
        addCollaborator("commonhaus-test/sponsors-test", botLogin);

        // Simple retrieval of UserInfo data (provided above)
        // Parse/population of GitHubUser object
        given()
                .log().all()
                .when().get("/me")
                .then()
                .log().all()
                .statusCode(200)
                .body("INFO.login", equalTo(botLogin));
    }

    @Test
    @TestSecurity(user = botLogin)
    @OidcSecurity(userinfo = {
            @UserInfo(key = "login", value = botLogin),
            @UserInfo(key = "id", value = botId + ""),
            @UserInfo(key = "node_id", value = botNodeId),
            @UserInfo(key = "avatar_url", value = "https://avatars.githubusercontent.com/u/156364140?v=4")
    })
    void testLoginEndpoint() throws Exception {
        // Redirect to member home page with the id in a cookie
        given()
                .log().all()
                .redirects().follow(false)
                .when().get("/login")
                .then()
                .log().all()
                .cookie("id", botNodeId)
                .statusCode(303)
                .assertThat()
                .cookie("id", detailedCookie()
                        .value("U_kgDOCVHtbA")
                        .path("/")
                        .secured(true)
                        .maxAge(60));
    }

    @Test
    @TestSecurity(user = botLogin)
    @OidcSecurity(userinfo = {
            @UserInfo(key = "login", value = botLogin),
            @UserInfo(key = "id", value = botId + ""),
            @UserInfo(key = "node_id", value = botNodeId),
            @UserInfo(key = "avatar_url", value = "https://avatars.githubusercontent.com/u/156364140?v=4")
    })
    void testGetCommonhausUserNotFound() throws Exception {
        GHUser botUser = sponsorMocks.github().getUser(botLogin);
        appendCachedTeam(sponsorsOrgName + "/team-quorum-default", botUser);
        addCollaborator(sponsorsRepo, "other");

        when(dataMocks.repository().getFileContent(anyString()))
                .thenThrow(new TestFileNotFoundException("test ex"));

        given()
                .log().all()
                .when()
                .get("/commonhaus")
                .then()
                .log().all()
                .statusCode(200)
                .body("HAUS.status", equalTo("UNKNOWN"));

        given()
                .log().all()
                .when()
                .get("/aliases")
                .then()
                .log().all()
                .statusCode(403)
                .body("HAUS.status", equalTo("UNKNOWN"));
    }

    @Test
    @TestSecurity(user = botLogin)
    @OidcSecurity(userinfo = {
            @UserInfo(key = "login", value = botLogin),
            @UserInfo(key = "id", value = botId + ""),
            @UserInfo(key = "node_id", value = botNodeId),
            @UserInfo(key = "avatar_url", value = "https://avatars.githubusercontent.com/u/156364140?v=4")
    })
    void testGetCommonhausUserBadnessHappens() throws Exception {
        GHUser botUser = sponsorMocks.github().getUser(botLogin);
        appendCachedTeam(sponsorsOrgName + "/team-quorum-default", botUser);

        when(dataMocks.repository().getFileContent(anyString()))
                .thenThrow(new IOException("Badness"));

        given()
                .log().all()
                .when()
                .get("/commonhaus")
                .then()
                .log().all()
                .statusCode(500);

        errorMailExpected = true;
        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() != 0);
        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).hasSize(1);
        assertThat(mailbox.getMailsSentTo("repo-errors@example.com")).hasSize(0);
    }

    @Test
    @TestSecurity(user = botLogin)
    @OidcSecurity(userinfo = {
            @UserInfo(key = "login", value = botLogin),
            @UserInfo(key = "id", value = botId + ""),
            @UserInfo(key = "node_id", value = botNodeId),
            @UserInfo(key = "avatar_url", value = "https://avatars.githubusercontent.com/u/156364140?v=4")
    })
    void testGetCommonhausUser() throws Exception {
        mockExistingCommonhausData(UserPath.WITH_ATTESTATION);

        GHUser botUser = sponsorMocks.github().getUser(botLogin);
        appendCachedTeam(sponsorsOrgName + "/team-quorum-default", botUser);
        addCollaborator(sponsorsRepo, botLogin);

        given()
                .log().all()
                .when()
                .get("/commonhaus")
                .then()
                .log().all()
                .statusCode(200)
                .body("HAUS.goodUntil.attestation.test.withStatus", equalTo("UNKNOWN"));
    }

    @Test
    @TestSecurity(user = botLogin)
    @OidcSecurity(userinfo = {
            @UserInfo(key = "login", value = botLogin),
            @UserInfo(key = "id", value = botId + ""),
            @UserInfo(key = "node_id", value = botNodeId),
            @UserInfo(key = "avatar_url", value = "https://avatars.githubusercontent.com/u/156364140?v=4")
    })
    void testGetCommonhausUserWithEmail() throws Exception {
        mockExistingCommonhausData(UserPath.WITH_EMAIL_MEMBER);

        GHUser botUser = sponsorMocks.github().getUser(botLogin);
        appendCachedTeam(sponsorsOrgName + "/team-quorum-default", botUser);
        addCollaborator(sponsorsRepo, botLogin);

        given()
                .log().all()
                .when()
                .get("/aliases")
                .then()
                .log().all()
                .statusCode(200)
                .body("HAUS.status", equalTo("ACTIVE"))
                .body("HAUS.services.forwardEmail.hasDefaultAlias", equalTo(true))
                .body("HAUS.services.forwardEmail.altAlias", contains("here@project.org"))
                .body("ALIAS", allOf(
                        hasKey("commonhaus-bot@example.com"),
                        hasKey("here@project.org")));
    }

    @Test
    @TestSecurity(user = botLogin)
    @OidcSecurity(userinfo = {
            @UserInfo(key = "login", value = botLogin),
            @UserInfo(key = "id", value = botId + ""),
            @UserInfo(key = "node_id", value = botNodeId),
            @UserInfo(key = "avatar_url", value = "https://avatars.githubusercontent.com/u/156364140?v=4")
    })
    void testUpdateCommonhausUserWithEmail() throws Exception {
        mockExistingCommonhausData(UserPath.WITH_EMAIL_COMMITTEE);

        GHUser botUser = sponsorMocks.github().getUser(botLogin);
        appendCachedTeam(sponsorsOrgName + "/cf-voting", botUser);
        addCollaborator(sponsorsRepo, "otherUser");

        Map<String, Set<String>> input = Map.of(
                botLogin, Set.of("target@example.com"));

        given()
                .log().all()
                .when()
                .contentType(ContentType.JSON)
                .body(mapper.writeValueAsString(input))
                .post("/aliases")
                .then()
                .log().all()
                .statusCode(200)
                .body("HAUS.status", equalTo("COMMITTEE"))
                .body("HAUS.services.forwardEmail.hasDefaultAlias", equalTo(true))
                .body("ALIAS", hasKey("commonhaus-bot@example.com"));
    }

    @Test
    @TestSecurity(user = botLogin)
    @OidcSecurity(userinfo = {
            @UserInfo(key = "login", value = botLogin),
            @UserInfo(key = "id", value = botId + ""),
            @UserInfo(key = "node_id", value = botNodeId),
            @UserInfo(key = "avatar_url", value = "https://avatars.githubusercontent.com/u/156364140?v=4")
    })
    void testGetCommonhausSponsor() throws Exception {
        mockExistingCommonhausData(UserPath.WITH_EMAIL_SPONSOR);

        GHUser botUser = sponsorMocks.github().getUser(botLogin);
        appendCachedTeam(sponsorsOrgName + "/team-quorum-default", botUser);
        addCollaborator(sponsorsRepo, botLogin);

        given()
                .log().all()
                .when()
                .get("/aliases")
                .then()
                .log().all()
                .statusCode(403)
                .body("HAUS.status", equalTo("SPONSOR"))
                .body("HAUS.services.forwardEmail.hasDefaultAlias", equalTo(false))
                .body("ALIAS", nullValue());
    }

    @Test
    @TestSecurity(user = botLogin)
    @OidcSecurity(userinfo = {
            @UserInfo(key = "login", value = botLogin),
            @UserInfo(key = "id", value = botId + ""),
            @UserInfo(key = "node_id", value = botNodeId),
            @UserInfo(key = "avatar_url", value = "https://avatars.githubusercontent.com/u/156364140?v=4")
    })
    void testGetCommonhausContributor() throws Exception {
        mockExistingCommonhausData(UserPath.WITH_EMAIL_CONTRIBUTOR);

        GHUser botUser = sponsorMocks.github().getUser(botLogin);
        appendCachedTeam(sponsorsOrgName + "/team-quorum-default", botUser);
        addCollaborator(sponsorsRepo, botLogin);

        given()
                .log().all()
                .when()
                .get("/aliases")
                .then()
                .log().all()
                .statusCode(200)
                .body("HAUS.status", equalTo("CONTRIBUTOR"))
                .body("HAUS.services.forwardEmail.hasDefaultAlias", equalTo(false))
                .body("HAUS.services.forwardEmail.altAlias", contains("here@project.org"))
                .body("ALIAS", allOf(
                        not(hasKey("commonhaus-bot@example.com")),
                        hasKey("here@project.org")));
    }

    @Test
    @TestSecurity(user = botLogin)
    @OidcSecurity(userinfo = {
            @UserInfo(key = "login", value = botLogin),
            @UserInfo(key = "id", value = botId + ""),
            @UserInfo(key = "node_id", value = botNodeId),
            @UserInfo(key = "avatar_url", value = "https://avatars.githubusercontent.com/u/156364140?v=4")
    })
    void testPutAttestation() throws Exception {
        LocalDate date = LocalDate.now().plusYears(1);
        String YMD = date.format(DateTimeFormatter.ISO_LOCAL_DATE);

        AttestationPost attestation = new AttestationPost(
                "member",
                "draft");
        String attestJson = mapper.writeValueAsString(attestation);

        GHContentBuilder builder = Mockito.mock(GHContentBuilder.class);
        mockExistingCommonhausData(UserPath.WITH_ATTESTATION); // getFileContent(anyString())
        mockUpdateCommonhausData(builder, UserPath.WITH_ATTESTATION);

        GHUser botUser = sponsorMocks.github().getUser(botLogin);
        appendCachedTeam(sponsorsOrgName + "/cf-voting", botUser);
        addCollaborator(sponsorsRepo, botLogin);

        given()
                .log().all()
                .when()
                .contentType(ContentType.JSON)
                .body(attestJson)
                .post("/commonhaus/attest")
                .then()
                .log().all()
                .statusCode(200)
                // this will match the mock-return value above (mockUpdateCommonhausData)
                // we will verify the captured argument below
                .body("HAUS.goodUntil.attestation.test.withStatus", equalTo("UNKNOWN"));

        // Verify captured input (common test output)
        final ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(builder).path(pathCaptor.capture());
        assertThat(pathCaptor.getValue()).isEqualTo("data/users/156364140.yaml");

        final ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(builder).content(contentCaptor.capture());

        CommonhausUser result = ctx.yamlMapper().readValue(contentCaptor.getValue(),
                CommonhausUser.class);
        assertThat(result.status()).isEqualTo(MemberStatus.COMMITTEE);

        assertThat(result.goodUntil().attestation()).hasSize(2);
        assertThat(result.goodUntil().attestation()).containsKey("member");
        Attestation att = result.goodUntil().attestation().get("member");
        assertThat(att.date()).isEqualTo(YMD);
        assertThat(att.version()).isEqualTo("draft");
        assertThat(att.withStatus()).isEqualTo(MemberStatus.COMMITTEE);
    }

    @Test
    @TestSecurity(user = botLogin)
    @OidcSecurity(userinfo = {
            @UserInfo(key = "login", value = botLogin),
            @UserInfo(key = "id", value = botId + ""),
            @UserInfo(key = "node_id", value = botNodeId),
            @UserInfo(key = "avatar_url", value = "https://avatars.githubusercontent.com/u/156364140?v=4")
    })
    void testPutUnknownAttestation() throws Exception {
        GHUser botUser = sponsorMocks.github().getUser(botLogin);
        appendCachedTeam(sponsorsOrgName + "/team-quorum-default", botUser);
        addCollaborator(sponsorsRepo, botLogin);

        AttestationPost attestation = new AttestationPost(
                "unknown",
                "draft");
        String attestJson = mapper.writeValueAsString(attestation);

        given()
                .log().all()
                .when()
                .contentType(ContentType.JSON)
                .body(attestJson)
                .post("/commonhaus/attest")
                .then()
                .log().all()
                .statusCode(400);
    }

    @Test
    @TestSecurity(user = botLogin)
    @OidcSecurity(userinfo = {
            @UserInfo(key = "login", value = botLogin),
            @UserInfo(key = "id", value = botId + ""),
            @UserInfo(key = "node_id", value = botNodeId),
            @UserInfo(key = "avatar_url", value = "https://avatars.githubusercontent.com/u/156364140?v=4")
    })
    void testPutAttestations() throws Exception {
        LocalDate date = LocalDate.now().plusYears(1);
        String YMD = date.format(DateTimeFormatter.ISO_LOCAL_DATE);

        List<AttestationPost> attestations = List.of(
                new AttestationPost("member", "draft"),
                new AttestationPost("coc", "2.0"));
        String attestJson = mapper.writeValueAsString(attestations);

        GHContentBuilder builder = Mockito.mock(GHContentBuilder.class);
        mockExistingCommonhausData(UserPath.WITH_ATTESTATION); // getFileContent(anyString())
        mockUpdateCommonhausData(builder, UserPath.WITH_ATTESTATION);

        GHUser botUser = sponsorMocks.github().getUser(botLogin);
        appendCachedTeam(sponsorsOrgName + "/team-quorum-default", botUser);
        addCollaborator(sponsorsRepo, botLogin);

        given()
                .log().all()
                .when()
                .contentType(ContentType.JSON)
                .body(attestJson)
                .post("/commonhaus/attest/all")
                .then()
                .log().all()
                .statusCode(200)
                .body("HAUS.goodUntil.attestation.test.withStatus", equalTo("UNKNOWN"));

        await().atMost(1, TimeUnit.SECONDS).until(() -> updateQueue.isEmpty());

        // Verify captured input (common test output)
        final ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(builder).content(contentCaptor.capture());

        var result = ctx.yamlMapper().readValue(contentCaptor.getValue(), CommonhausUser.class);
        assertThat(result.status()).isEqualTo(MemberStatus.ACTIVE);

        assertThat(result.goodUntil().attestation()).hasSize(3);
        assertThat(result.goodUntil().attestation()).containsKey("test");
        assertThat(result.goodUntil().attestation()).containsKey("member");
        assertThat(result.goodUntil().attestation()).containsKey("coc");

        var att = result.goodUntil().attestation().get("member");
        assertThat(att.date()).isEqualTo(YMD);
        assertThat(att.version()).isEqualTo("draft");
        assertThat(att.withStatus()).isEqualTo(MemberStatus.ACTIVE);

        att = result.goodUntil().attestation().get("coc");
        assertThat(att.date()).isEqualTo(YMD);
        assertThat(att.version()).isEqualTo("2.0");
        assertThat(att.withStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    @TestSecurity(user = botLogin)
    @OidcSecurity(userinfo = {
            @UserInfo(key = "login", value = botLogin),
            @UserInfo(key = "id", value = botId + ""),
            @UserInfo(key = "node_id", value = botNodeId),
            @UserInfo(key = "avatar_url", value = "https://avatars.githubusercontent.com/u/156364140?v=4")
    })
    void testGetApplication() throws Exception {
        GHUser botUser = sponsorMocks.github().getUser(botLogin);
        appendCachedTeam(sponsorsOrgName + "/team-quorum-default", botUser);
        addCollaborator(sponsorsRepo, botLogin);

        GHContentBuilder builder = Mockito.mock(GHContentBuilder.class);
        mockExistingCommonhausData(UserPath.WITH_APPLICATION); // getFileContent(anyString())
        mockUpdateCommonhausData(builder, UserPath.WITH_APPLICATION);

        setupGraphQLProcessing(dataMocks,
                MemberQueryResponse.APPLICATION_MATCH,
                MemberQueryResponse.QUERY_COMMENTS);

        given()
                .log().all()
                .when()
                .get("/apply")
                .then()
                .log().all()
                .statusCode(200)
                .body("HAUS.status", equalTo("PENDING"))
                .body("APPLY.feedback.htmlContent", equalTo("<p>Feedback</p>\n"));
    }

    @Test
    @TestSecurity(user = botLogin)
    @OidcSecurity(userinfo = {
            @UserInfo(key = "login", value = botLogin),
            @UserInfo(key = "id", value = botId + ""),
            @UserInfo(key = "node_id", value = botNodeId),
            @UserInfo(key = "avatar_url", value = "https://avatars.githubusercontent.com/u/156364140?v=4")
    })
    void testGetInvalidApplication() throws Exception {
        GHUser botUser = sponsorMocks.github().getUser(botLogin);
        appendCachedTeam(sponsorsOrgName + "/team-quorum-default", botUser);
        addCollaborator(sponsorsRepo, botLogin);

        // dataStore.getFileContent(anyString()))
        mockExistingCommonhausData(UserPath.WITH_APPLICATION);

        // dataStore.createContent()
        GHContentBuilder builder = Mockito.mock(GHContentBuilder.class);
        mockUpdateCommonhausData(builder, UserPath.WITH_APPLICATION);

        setupGraphQLProcessing(dataMocks,
                MemberQueryResponse.APPLICATION_BAD_TITLE);

        given()
                .log().all()
                .when()
                .get("/apply")
                .then()
                .log().all()
                .statusCode(400);

        // wait for pending queue to drain (persisted data)
        await().atMost(1, TimeUnit.SECONDS).until(() -> updateQueue.isEmpty());

        // read captured parameter of persisted file content
        final ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(builder).content(contentCaptor.capture());

        var result = ctx.yamlMapper().readValue(contentCaptor.getValue(), CommonhausUser.class);
        assertThat(result.application()).isNull();

        verifyGraphQLProcessing(dataMocks, true);
    }

    @Test
    @TestSecurity(user = botLogin)
    @OidcSecurity(userinfo = {
            @UserInfo(key = "login", value = botLogin),
            @UserInfo(key = "id", value = botId + ""),
            @UserInfo(key = "node_id", value = botNodeId),
            @UserInfo(key = "avatar_url", value = "https://avatars.githubusercontent.com/u/156364140?v=4")
    })
    void testSubmitApplication() throws Exception {
        GHUser botUser = sponsorMocks.github().getUser(botLogin);
        appendCachedTeam(sponsorsOrgName + "/team-quorum-default", botUser);
        addCollaborator(sponsorsRepo, botLogin);

        ApplicationPost application = new ApplicationPost("unknown", "draft");
        String applyJson = mapper.writeValueAsString(application);

        // dataStore.getFileContent(anyString()))
        mockExistingCommonhausData(UserPath.WITH_APPLICATION);

        // dataStore.createContent() -- create a normal user
        GHContentBuilder builder = Mockito.mock(GHContentBuilder.class);
        mockUpdateCommonhausData(builder, UserPath.WITH_APPLICATION);

        setupGraphQLProcessing(dataMocks,
                MemberQueryResponse.APPLICATION_MATCH,
                MemberQueryResponse.UPDATE_ISSUE);

        given()
                .log().all()
                .when()
                .contentType(ContentType.JSON)
                .body(applyJson)
                .post("/apply")
                .then()
                .log().all()
                .statusCode(200)
                .body("HAUS.status", equalTo("PENDING")) // mock response
                .body("APPLY.created", notNullValue()) // from mutableUpdateIssue.json
                .body("APPLY.updated", notNullValue()) // from mutableUpdateIssue.json
                .body("APPLY.contributions", equalTo("testContrib")) // from mutableUpdateIssue.json
                .body("APPLY.additionalNotes", equalTo("testNotes")); // from mutableUpdateIssue.json

        // Look at data passed to mutable operation to update user data
        final ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(builder).content(contentCaptor.capture());

        var result = ctx.yamlMapper().readValue(contentCaptor.getValue(), CommonhausUser.class);

        assertThat(result.application()).isNotNull();
        assertThat(result.status()).isEqualTo(MemberStatus.PENDING); // changed from UNKNOWN -> PENDING

        verifyGraphQLProcessing(dataMocks, true);
    }
}
