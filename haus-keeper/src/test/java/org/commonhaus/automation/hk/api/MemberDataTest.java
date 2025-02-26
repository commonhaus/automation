package org.commonhaus.automation.hk.api;

import static io.restassured.RestAssured.given;
import static io.restassured.matcher.RestAssuredMatchers.detailedCookie;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

import jakarta.inject.Inject;

import org.commonhaus.automation.hk.api.MemberApplicationProcess.ApplicationPost;
import org.commonhaus.automation.hk.api.MemberAttestationResource.AttestationPost;
import org.commonhaus.automation.hk.data.CommonhausUser;
import org.commonhaus.automation.hk.data.CommonhausUserData;
import org.commonhaus.automation.hk.data.CommonhausUserData.Attestation;
import org.commonhaus.automation.hk.data.MemberStatus;
import org.commonhaus.automation.hk.github.HausKeeperTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHContentBuilder;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.mockito.ArgumentCaptor;

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
    static final String USER_WITH_APPLICATION = "src/test/resources/haus-member-application.yaml";

    @Inject
    ObjectMapper mapper;

    @Override
    @BeforeEach
    protected void init() throws Exception {
        super.init();
        setupInstallationRepositories();
        setupBotLogin();
        setupMockTeam();
        setUserManagementConfig();
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

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() == 0);
        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).hasSize(0);
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
    void testUserInfoEndpoint() throws Exception {
        addCollaborator("commonhaus-test/sponsors-test", botLogin);

        // Make known user: add to sponsors-test repository
        GHRepository repo = sponsorMocks.repository();
        when(repo.getCollaboratorNames()).thenReturn(Set.of(botLogin));

        // Simple retrieval of UserInfo data (provided above)
        // Parse/population of GitHubUser object
        given()
                .log().all()
                .when().get("/me")
                .then()
                .log().all()
                .statusCode(200)
                .body("INFO.login", equalTo(botLogin));

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() == 0);
        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).hasSize(0);
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

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() == 0);
        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).hasSize(0);
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
    void testGetCommonhausUserNotFound() throws Exception {
        GHUser botUser = sponsorMocks.github().getUser(botLogin);
        appendCachedTeam(sponsorsOrgName + "/team-quorum-default", botUser);

        GHRepository dataStore = dataMocks.repository();
        when(dataStore.getFileContent(anyString())).thenThrow(new GHFileNotFoundException("Badness"));

        given()
                .log().all()
                .when()
                .get("/commonhaus")
                .then()
                .log().all()
                .statusCode(200)
                .body("HAUS.status", equalTo("UNKNOWN"));

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() == 0);
        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).hasSize(0);
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
    void testGetCommonhausUserBadnessHappens() throws Exception {
        GHUser botUser = sponsorMocks.github().getUser(botLogin);
        appendCachedTeam(sponsorsOrgName + "/team-quorum-default", botUser);

        GHRepository dataStore = dataMocks.repository();
        when(dataStore.getFileContent(anyString())).thenThrow(new IOException("Badness"));

        given()
                .log().all()
                .when()
                .get("/commonhaus")
                .then()
                .log().all()
                .statusCode(500);

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

        mockExistingCommonhausData();

        GHUser botUser = sponsorMocks.github().getUser(botLogin);
        appendCachedTeam(sponsorsOrgName + "/team-quorum-default", botUser);

        given()
                .log().all()
                .when()
                .get("/commonhaus")
                .then()
                .log().all()
                .statusCode(200)
                .body("HAUS.goodUntil.attestation.test.withStatus", equalTo("UNKNOWN"));

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() == 0);
        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).hasSize(0);
        assertThat(mailbox.getMailsSentTo("repo-errors@example.com")).hasSize(0);
    }

    @Test
    void testGetCommonhausUserStatus() throws Exception {

        setUserManagementConfig();
        ctx.getStatusForRole("sponsor");

        CommonhausUser user = new CommonhausUser.Builder()
                .withId(12345)
                .withData(new CommonhausUserData())
                .build();

        assertThat(user.status()).isEqualTo(MemberStatus.UNKNOWN);

        Set<String> roles = Set.of("sponsor");
        boolean update = user.statusUpdateRequired(ctx, roles);
        assertThat(update).isTrue();
        user.updateMemberStatus(ctx, roles);
        assertThat(user.status()).isEqualTo(MemberStatus.SPONSOR);

        roles = Set.of("sponsor", "member", "egc");
        update = user.statusUpdateRequired(ctx, roles);
        assertThat(update).isTrue();
        user.updateMemberStatus(ctx, roles);
        assertThat(user.status()).isEqualTo(MemberStatus.COMMITTEE);
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

        mockExistingCommonhausData(); // pre-existing data

        GHContentBuilder builder = mockUpdateCommonhausData();

        GHUser botUser = sponsorMocks.github().getUser(botLogin);
        appendCachedTeam(sponsorsOrgName + "/cf-voting", botUser);

        given()
                .log().all()
                .when()
                .contentType(ContentType.JSON)
                .body(attestJson)
                .post("/commonhaus/attest")
                .then()
                .log().all()
                .statusCode(200)
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

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() == 0);
        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).hasSize(0);
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
    void testPutUnknownAttestation() throws Exception {
        GHUser botUser = sponsorMocks.github().getUser(botLogin);
        appendCachedTeam(sponsorsOrgName + "/team-quorum-default", botUser);

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

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() == 0);
        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).hasSize(0);
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
    void testPutAttestations() throws Exception {
        LocalDate date = LocalDate.now().plusYears(1);
        String YMD = date.format(DateTimeFormatter.ISO_LOCAL_DATE);

        List<AttestationPost> attestations = List.of(
                new AttestationPost("member", "draft"),
                new AttestationPost("coc", "2.0"));
        String attestJson = mapper.writeValueAsString(attestations);

        GHContentBuilder builder = mockUpdateCommonhausData();

        GHUser botUser = sponsorMocks.github().getUser(botLogin);
        appendCachedTeam(sponsorsOrgName + "/team-quorum-default", botUser);

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

        // Verify captured input (common test output)
        final ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(builder).content(contentCaptor.capture());

        var result = ctx.yamlMapper().readValue(contentCaptor.getValue(), CommonhausUser.class);
        assertThat(result.status()).isEqualTo(MemberStatus.ACTIVE);

        assertThat(result.goodUntil().attestation()).hasSize(2);
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

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() == 0);
        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).hasSize(0);
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
    void testGetUnknownApplication() throws Exception {
        GHUser botUser = sponsorMocks.github().getUser(botLogin);
        appendCachedTeam(sponsorsOrgName + "/team-quorum-default", botUser);

        mockExistingCommonhausData();

        given()
                .log().all()
                .when()
                .get("/apply")
                .then()
                .log().all()
                .statusCode(404);

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() == 0);
        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).hasSize(0);
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
    void testGetInvalidApplication() throws Exception {
        GHUser botUser = sponsorMocks.github().getUser(botLogin);
        appendCachedTeam(sponsorsOrgName + "/team-quorum-default", botUser);

        // dataStore.getFileContent(anyString()))
        mockExistingCommonhausData(USER_WITH_APPLICATION);

        // dataStore.createContent()
        GHContentBuilder builder = mockUpdateCommonhausData(USER_WITH_APPLICATION);

        setupGraphQLProcessing(dataMocks,
                QueryReponse.APPLICATION_BAD_TITLE);

        given()
                .log().all()
                .when()
                .get("/apply")
                .then()
                .log().all()
                .statusCode(404);

        final ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(builder).content(contentCaptor.capture());

        var result = ctx.yamlMapper().readValue(contentCaptor.getValue(), CommonhausUser.class);
        assertThat(result.application()).isNull();

        for (String cue : graphQueries) {
            verify(dataMocks.dql(), timeout(500))
                    .executeSync(contains(cue), anyMap());
        }
        verifyNoMoreInteractions(dataMocks.dql());

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() == 0);
        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).hasSize(0);
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
    void testGetApplicationWithFeedback() throws Exception {
        GHUser botUser = sponsorMocks.github().getUser(botLogin);
        appendCachedTeam(sponsorsOrgName + "/team-quorum-default", botUser);

        // dataStore.getFileContent(anyString()))
        mockExistingCommonhausData(USER_WITH_APPLICATION);

        // dataStore.createContent() -- update to regular user
        GHContentBuilder builder = mockUpdateCommonhausData();

        setupGraphQLProcessing(dataMocks,
                QueryReponse.APPLICATION_MATCH,
                QueryReponse.QUERY_COMMENTS);

        given()
                .log().all()
                .when()
                .get("/apply")
                .then()
                .log().all()
                .statusCode(200)
                .body("APPLY.feedback.htmlContent", equalTo("<p>Feedback</p>\n"));

        final ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(builder).content(contentCaptor.capture());

        var result = ctx.yamlMapper().readValue(contentCaptor.getValue(), CommonhausUser.class);
        assertThat(result.application()).isNotNull();
        assertThat(result.status()).isEqualTo(MemberStatus.PENDING);

        for (String cue : graphQueries) {
            verify(dataMocks.dql(), timeout(500))
                    .executeSync(contains(cue), anyMap());
        }
        verifyNoMoreInteractions(dataMocks.dql());

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() == 0);
        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).hasSize(0);
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
    void testSubmitApplication() throws Exception {
        GHUser botUser = sponsorMocks.github().getUser(botLogin);
        appendCachedTeam(sponsorsOrgName + "/team-quorum-default", botUser);

        ApplicationPost application = new ApplicationPost("unknown", "draft");
        String applyJson = mapper.writeValueAsString(application);

        // dataStore.getFileContent(anyString()))
        mockExistingCommonhausData(USER_WITH_APPLICATION);

        // dataStore.createContent() -- create a normal user
        GHContentBuilder builder = mockUpdateCommonhausData();

        setupGraphQLProcessing(dataMocks,
                QueryReponse.APPLICATION_MATCH,
                QueryReponse.UPDATE_ISSUE);

        given()
                .log().all()
                .when()
                .contentType(ContentType.JSON)
                .body(applyJson)
                .post("/apply")
                .then()
                .log().all()
                .statusCode(200)
                .body("HAUS.goodUntil.attestation.test.withStatus", equalTo("UNKNOWN")) // mock response
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

        for (String cue : graphQueries) {
            verify(dataMocks.dql(), timeout(500))
                    .executeSync(contains(cue), anyMap());
        }
        verifyNoMoreInteractions(dataMocks.dql());

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() == 0);
        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).hasSize(0);
        assertThat(mailbox.getMailsSentTo("repo-errors@example.com")).hasSize(0);
    }

    enum QueryReponse implements MockResponse {
        APPLICATION_BAD_TITLE("query($id: ID!) {",
                "src/test/resources/github/queryIssue-ApplicationBadTitle.json"),

        APPLICATION_MATCH("query($id: ID!) {",
                "src/test/resources/github/queryIssue-ApplicationMatch.json"),

        QUERY_COMMENTS("comments(first: 50",
                "src/test/resources/github/queryComments.json"),

        UPDATE_ISSUE("updateIssue(input: {",
                "src/test/resources/github/mutableUpdateIssue.json"),
                ;

        String cue;
        Path path;

        QueryReponse(String cue, String path) {
            this.cue = cue;
            this.path = Path.of(path);
        }

        @Override
        public String cue() {
            return cue;
        }

        @Override
        public Path path() {
            return path;
        }

        @Override
        public long installationId() {
            return datastoreInstallationId;
        }

    }
}
