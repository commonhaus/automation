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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.commonhaus.automation.admin.api.CommonhausUser.Attestation;
import org.commonhaus.automation.admin.api.CommonhausUser.MemberStatus;
import org.commonhaus.automation.admin.github.AdminDataCache;
import org.commonhaus.automation.admin.github.AppContextService;
import org.commonhaus.automation.admin.github.ContextHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHContentBuilder;
import org.kohsuke.github.GHContentUpdateResponse;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHMyself;
import org.kohsuke.github.GHRepository;
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

        userGithub = setupUserGithub(ctx, mockContext.mocks, botNodeId);
        when(userGithub.isCredentialValid()).thenReturn(true);

        botGithub = setupBotGithub(ctx, mockContext.mocks);
        when(botGithub.isCredentialValid()).thenReturn(true);
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
        // we should return a 405
        given()
                .log().all()
                .when().get("/me")
                .then()
                .log().all()
                .statusCode(405);

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
        // Simple retrieval of UserInfo data (provided above)
        // Parse/population of GitHubUser object
        given()
                .log().all()
                .when().get("/me")
                .then()
                .log().all()
                .statusCode(200)
                .body("type", equalTo("INFO"))
                .body("payload.login", equalTo(botLogin));

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
                        .maxAge(30));

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
    void testMyself() throws Exception {

        GHMyself myself = mockContext.mocks.ghObject(GHMyself.class, botId);
        when(userGithub.getMyself()).thenReturn(myself);

        given()
                .log().all()
                .when()
                .get("/gh-emails")
                .then()
                .log().all()
                .statusCode(200)
                .body("type", equalTo("EMAIL"));

        verify(userGithub, Mockito.times(1)).getMyself();

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
        GHRepository dataStore = botGithub.getRepository(ctx.getDataStore());
        when(dataStore.getFileContent(anyString())).thenThrow(new GHFileNotFoundException("Badness"));

        given()
                .log().all()
                .when()
                .get("/commonhaus")
                .then()
                .log().all()
                .statusCode(200)
                .body("type", equalTo("HAUS"))
                .body("payload.status", equalTo("UNKNOWN"));

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

        given()
                .log().all()
                .when()
                .get("/commonhaus")
                .then()
                .log().all()
                .statusCode(200)
                .body("type", equalTo("HAUS"));

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
        final ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);

        CommonhausUser user = CommonhausUser.create(botLogin, botId, MemberStatus.SPONSOR);
        String userYaml = AppContextService.yamlMapper().writeValueAsString(user);

        Attestation attestation = new Attestation(
                MemberStatus.ACTIVE,
                "2024-04-25",
                "member",
                "draft",
                mapper.createObjectNode()
                        .put("bylaws", true));
        String attestJson = mapper.writeValueAsString(attestation);

        GHContent content = Mockito.mock(GHContent.class);
        when(content.read()).thenReturn(new ByteArrayInputStream(userYaml.getBytes()));
        when(content.getSha()).thenReturn("1234567890abcdef");

        GHContentUpdateResponse response = Mockito.mock(GHContentUpdateResponse.class);
        when(response.getContent()).thenReturn(content);

        GHContentBuilder builder = Mockito.mock(GHContentBuilder.class);
        when(builder.content(anyString())).thenReturn(builder);
        when(builder.message(anyString())).thenReturn(builder);
        when(builder.sha(anyString())).thenReturn(builder);
        when(builder.commit()).thenReturn(response);

        GHRepository dataStore = botGithub.getRepository(ctx.getDataStore());
        when(dataStore.createContent()).thenReturn(builder);

        given()
                .log().all()
                .when()
                .contentType(ContentType.JSON)
                .body(attestJson)
                .put("/commonhaus/attest")
                .then()
                .log().all()
                .statusCode(200)
                .body("type", equalTo("HAUS"));

        verify(builder).content(captor.capture());

        await().atMost(5, SECONDS).until(() -> mailbox.getTotalMessagesSent() == 0);
        assertThat(mailbox.getMailsSentTo("bot-errors@example.com")).hasSize(0);
        assertThat(mailbox.getMailsSentTo("repo-errors@example.com")).hasSize(0);
    }
}
