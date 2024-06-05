package org.commonhaus.automation.admin.api;

import static io.restassured.RestAssured.given;
import static io.restassured.matcher.RestAssuredMatchers.detailedCookie;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.commonhaus.automation.admin.AdminDataCache;
import org.commonhaus.automation.admin.api.CommonhausUser.Attestation;
import org.commonhaus.automation.admin.api.CommonhausUser.AttestationPost;
import org.commonhaus.automation.admin.api.CommonhausUser.MemberStatus;
import org.commonhaus.automation.admin.github.AppContextService;
import org.commonhaus.automation.admin.github.ContextHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHContentBuilder;
import org.kohsuke.github.GHContentUpdateResponse;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkiverse.githubapp.testing.internal.GitHubAppTestingContext;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.quarkus.test.security.oidc.UserInfo;
import io.restassured.http.ContentType;

@QuarkusTest
@GitHubAppTest
@TestHTTPEndpoint(MemberApi.class)
public class MemberDataTest extends ContextHelper {

    @Inject
    MockMailbox mailbox;

    @Inject
    AppContextService ctx;

    @Inject
    ObjectMapper mapper;

    GitHub userGithub;
    GitHub botGithub;
    GitHubAppTestingContext mockContext;

    @BeforeEach
    void init() throws IOException {
        mailbox.clear();
        Stream.of(AdminDataCache.values()).forEach(v -> v.invalidateAll());

        mockContext = GitHubAppTestingContext.get();

        botGithub = setupBotGithub(ctx, mockContext.mocks);
        when(botGithub.isCredentialValid()).thenReturn(true);

        setupMockTeam(mockContext.mocks);
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
        // If the user is unknown (not in a configured org or a contributor to specified repo),
        // we should return a 403
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
        // Make known user: add to sponsors-test repository
        GHRepository repo = mockContext.mocks.repository("commonhaus-test/sponsors-test");
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
        GHUser botUser = botGithub.getUser(botLogin);
        appendMockTeam(organizationName + "/team-quorum-default", botUser);

        GHRepository dataStore = botGithub.getRepository(ctx.getDataStore());
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
        GHUser botUser = botGithub.getUser(botLogin);
        appendMockTeam(organizationName + "/team-quorum-default", botUser);

        GHRepository dataStore = botGithub.getRepository(ctx.getDataStore());
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
        GHContent content = mock(GHContent.class);
        when(content.read()).thenReturn(Files.newInputStream(Path.of("src/test/resources/commonhaus-user.yaml")));
        when(content.getSha()).thenReturn("1234567890abcdef");

        GHRepository dataStore = botGithub.getRepository(ctx.getDataStore());
        when(dataStore.getFileContent(anyString())).thenReturn(content);

        GHUser botUser = botGithub.getUser(botLogin);
        appendMockTeam(organizationName + "/team-quorum-default", botUser);

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

        // pre-fetch
        GHContent content = mock(GHContent.class);
        when(content.read()).thenReturn(Files.newInputStream(Path.of("src/test/resources/commonhaus-user.yaml")));
        when(content.getSha()).thenReturn("1234567890abcdef");

        GHContentBuilder builder = Mockito.mock(GHContentBuilder.class);
        when(builder.content(anyString())).thenReturn(builder);
        when(builder.message(anyString())).thenReturn(builder);
        when(builder.path(anyString())).thenReturn(builder);
        when(builder.sha(anyString())).thenReturn(builder);

        GHContent responseContent = mock(GHContent.class);
        when(responseContent.read()).thenReturn(Files.newInputStream(Path.of("src/test/resources/commonhaus-user.yaml")));
        when(responseContent.getSha()).thenReturn("1234567890abcdef");

        GHRepository dataStore = botGithub.getRepository(ctx.getDataStore());
        when(dataStore.getFileContent(anyString())).thenReturn(content);
        when(dataStore.createContent()).thenReturn(builder);

        GHContentUpdateResponse response = Mockito.mock(GHContentUpdateResponse.class);
        when(response.getContent()).thenReturn(responseContent);

        when(builder.commit()).thenReturn(response);

        GHUser botUser = botGithub.getUser(botLogin);
        appendMockTeam(organizationName + "/cf-voting", botUser);

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

        CommonhausUser result = AppContextService.yamlMapper().readValue(contentCaptor.getValue(), CommonhausUser.class);
        assertThat(result.status()).isEqualTo(MemberStatus.COMMITTEE);

        assertThat(result.goodUntil().attestation).hasSize(2);
        assertThat(result.goodUntil().attestation).containsKey("member");
        Attestation att = result.goodUntil().attestation.get("member");
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
        GHUser botUser = botGithub.getUser(botLogin);
        appendMockTeam(organizationName + "/team-quorum-default", botUser);

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
        when(builder.content(anyString())).thenReturn(builder);
        when(builder.message(anyString())).thenReturn(builder);
        when(builder.path(anyString())).thenReturn(builder);
        when(builder.sha(anyString())).thenReturn(builder);

        GHContent responseContent = mock(GHContent.class);
        when(responseContent.read()).thenReturn(Files.newInputStream(Path.of("src/test/resources/commonhaus-user.yaml")));
        when(responseContent.getSha()).thenReturn("1234567890abcdef");

        GHRepository dataStore = botGithub.getRepository(ctx.getDataStore());
        when(dataStore.createContent()).thenReturn(builder);

        GHContentUpdateResponse response = Mockito.mock(GHContentUpdateResponse.class);
        when(response.getContent()).thenReturn(responseContent);

        when(builder.commit()).thenReturn(response);

        GHUser botUser = botGithub.getUser(botLogin);
        appendMockTeam(organizationName + "/team-quorum-default", botUser);

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

        var result = AppContextService.yamlMapper().readValue(contentCaptor.getValue(), CommonhausUser.class);
        assertThat(result.status()).isEqualTo(MemberStatus.ACTIVE);

        assertThat(result.goodUntil().attestation).hasSize(2);
        assertThat(result.goodUntil().attestation).containsKey("member");
        assertThat(result.goodUntil().attestation).containsKey("coc");

        var att = result.goodUntil().attestation.get("member");
        assertThat(att.date()).isEqualTo(YMD);
        assertThat(att.version()).isEqualTo("draft");
        assertThat(att.withStatus()).isEqualTo(MemberStatus.ACTIVE);

        att = result.goodUntil().attestation.get("coc");
        assertThat(att.date()).isEqualTo(YMD);
        assertThat(att.version()).isEqualTo("2.0");
        assertThat(att.withStatus()).isEqualTo(MemberStatus.ACTIVE);

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() == 0);
        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).hasSize(0);
        assertThat(mailbox.getMailsSentTo("repo-errors@example.com")).hasSize(0);
    }
}
