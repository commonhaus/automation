package org.commonhaus.automation.hk.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.PackagedException;
import org.commonhaus.automation.github.context.TestRuntimeException;
import org.commonhaus.automation.hk.data.CommonhausUser;
import org.commonhaus.automation.hk.data.MemberStatus;
import org.commonhaus.automation.hk.github.AppContextService;
import org.commonhaus.automation.hk.github.CommonhausDatastore;
import org.commonhaus.automation.hk.github.HausKeeperTestBase;
import org.commonhaus.automation.hk.member.MemberApplicationProcess.ApplicationPost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHContentBuilder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class ApplicationProcessUserTest extends HausKeeperTestBase {
    @Inject
    MemberApplicationProcess process;

    @Inject
    CommonhausDatastore datastore;

    @Inject
    AppContextService ctx;

    @BeforeEach
    @Override
    protected void init() throws Exception {
        super.init();
        setupInstallationRepositories();
        setupBotLogin();
    }

    @AfterEach
    void cleanup() {
        await().atMost(2, TimeUnit.SECONDS).until(() -> updateQueue.isEmpty());
        assertNoErrorEmails();
    }

    @Test
    public void testGetApplicationExisting() throws Exception {
        // existing user w/ application + status unknown
        // should update to pending.

        // preset datastore cache
        mockExistingCommonhausData(UserPath.WITH_APPLICATION);
        CommonhausUser user = datastore.getCommonhausUser(mockMemberInfo, false, false);
        assertThat(user).isNotNull();
        assertThat(user.status()).isEqualTo(MemberStatus.UNKNOWN);

        // mock graphql responses
        setupGraphQLProcessing(dataMocks,
                MemberQueryResponse.APPLICATION_MATCH,
                MemberQueryResponse.QUERY_NO_COMMENTS);

        // user status update UNKNOWN -> PENDING
        GHContentBuilder builder = Mockito.mock(GHContentBuilder.class);
        mockUpdateCommonhausData(builder, UserPath.WITH_APPLICATION);

        var apiResult = process.getUserApplication(mockMemberInfo, user);
        await()
                .atMost(1, TimeUnit.SECONDS)
                .until(() -> updateQueue.isEmpty());

        assertThat(apiResult.status()).isEqualTo(Response.Status.OK);
        assertThat(apiResult.user().status()).isEqualTo(MemberStatus.PENDING);
        assertThat(apiResult.user().application()).isNotNull();

        verifyGraphQLProcessing(dataMocks, true);
        assertPersistedContentEquals(apiResult.user(), builder);
    }

    @Test
    public void testGetApplicationIssueNotFound() throws Throwable {
        // findUserApplication -- not found
        // existing user w/ application + status unknown
        // should update to pending.

        // preset datastore cache
        mockExistingCommonhausData(UserPath.WITH_APPLICATION);
        CommonhausUser user = datastore.getCommonhausUser(mockMemberInfo, false, false);
        assertThat(user).isNotNull();
        assertThat(user.status()).isEqualTo(MemberStatus.UNKNOWN);

        // remove missing application issue
        mockGraphQLNotFound(dataMocks, "query($id: ID!) {");
        GHContentBuilder builder = Mockito.mock(GHContentBuilder.class);
        mockUpdateCommonhausData(builder, UserPath.WITH_APPLICATION);

        var apiResult = process.getUserApplication(mockMemberInfo, user);
        await().atMost(1, TimeUnit.SECONDS).until(() -> updateQueue.isEmpty());

        assertThat(apiResult.status()).isEqualTo(Response.Status.NOT_FOUND);
        assertThat(apiResult.user().application()).isNull();

        verifyGraphQLProcessing(dataMocks, false);
        verify(dataMocks.dql(), timeout(500))
                .executeSync(contains("query($id: ID!) {"), anyMap());

        assertPersistedContentEquals(apiResult.user(), builder);
    }

    @Test
    public void testGetApplicationBadRequest() throws Exception {
        mockExistingCommonhausData(UserPath.WITH_APPLICATION);

        // The issue doesn't match / isn't recognized; treat as not found
        setupGraphQLProcessing(dataMocks,
                MemberQueryResponse.APPLICATION_BAD_TITLE);

        GHContentBuilder builder = Mockito.mock(GHContentBuilder.class);
        mockUpdateCommonhausData(builder, UserPath.WITH_APPLICATION);

        // preset datastore cache
        CommonhausUser user = datastore.getCommonhausUser(mockMemberInfo, false, false);

        var apiResult = process.getUserApplication(mockMemberInfo, user);
        await().atMost(1, TimeUnit.SECONDS).until(() -> updateQueue.isEmpty());

        final ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(builder).content(contentCaptor.capture());

        assertThat(apiResult.status()).isEqualTo(Response.Status.BAD_REQUEST);
        assertThat(apiResult.user().status()).isEqualTo(MemberStatus.UNKNOWN);
        assertThat(apiResult.user().application()).isNull();

        verifyGraphQLProcessing(dataMocks, true);
        assertPersistedContentEquals(apiResult.user(), builder);
    }

    @Test
    public void testSetApplicationNotFoundCreate() throws Exception {
        mockExistingCommonhausData(UserPath.NEW_USER);

        setupGraphQLProcessing(dataMocks,
                MemberQueryResponse.CREATE_ISSUE);

        GHContentBuilder builder = Mockito.mock(GHContentBuilder.class);
        mockUpdateCommonhausData(builder, UserPath.WITH_APPLICATION);

        // preset datastore cache
        CommonhausUser user = datastore.getCommonhausUser(mockMemberInfo, false, false);

        ApplicationPost application = new ApplicationPost("unknown", "draft");

        var apiResult = process.setUserApplication(mockMemberInfo, user, application);
        await().atMost(1, TimeUnit.SECONDS).until(() -> updateQueue.isEmpty());

        assertThat(apiResult.status()).isEqualTo(Response.Status.OK);
        assertThat(apiResult.user().status()).isEqualTo(MemberStatus.PENDING);
        assertThat(apiResult.user().application()).isNotNull();

        verifyGraphQLProcessing(dataMocks, false);
        assertPersistedContentEquals(apiResult.user(), builder);
    }

    @Test
    public void testSetApplicationNotOwner() throws Exception {
        // Should new application
        mockExistingCommonhausData(UserPath.WITH_APPLICATION);
        GHContentBuilder builder = Mockito.mock(GHContentBuilder.class);
        mockUpdateCommonhausData(builder, UserPath.WITH_APPLICATION);

        // Application is wrong (somehow belongs to a different user)
        // replace with new application, update record
        setupGraphQLProcessing(dataMocks,
                MemberQueryResponse.APPLICATION_OTHER_OWNER,
                MemberQueryResponse.CREATE_ISSUE);

        // preset datastore cache
        CommonhausUser user = datastore.getCommonhausUser(mockMemberInfo, false, false);

        ApplicationPost application = new ApplicationPost("unknown", "draft");
        var apiResult = process.setUserApplication(mockMemberInfo, user, application);
        await().atMost(1, TimeUnit.SECONDS).until(() -> updateQueue.isEmpty());

        assertThat(apiResult.status()).isEqualTo(Response.Status.OK);
        assertThat(apiResult.user().status()).isEqualTo(MemberStatus.PENDING);
        assertThat(apiResult.user().application()).isNotNull();

        verifyGraphQLProcessing(dataMocks, true);
        assertPersistedContentEquals(apiResult.user(), builder);
    }

    @Test
    public void testGetSetThrows() throws Exception {
        // prime user data
        mockExistingCommonhausData(UserPath.WITH_APPLICATION);
        CommonhausUser user = datastore.getCommonhausUser(mockMemberInfo, false, false);

        mockGraphQLException(dataMocks, "query($id: ID!) {", new TestRuntimeException("failed on purpose"));

        assertThrows(PackagedException.class, () -> {
            process.getUserApplication(mockMemberInfo, user);
        });

        assertThrows(PackagedException.class, () -> {
            ApplicationPost post = new ApplicationPost("unknown", "draft");
            process.setUserApplication(mockMemberInfo, user, post);
        });
    }

    void assertPersistedContentEquals(CommonhausUser expected, GHContentBuilder builder) throws Exception {
        final ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(builder).path(pathCaptor.capture());
        assertThat(pathCaptor.getValue()).isEqualTo("data/users/156364140.yaml");

        final ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(builder).content(contentCaptor.capture());
        var persistResult = ctx.yamlMapper().readValue(contentCaptor.getValue(),
                CommonhausUser.class);

        String expectedStr = ctx.yamlMapper().writeValueAsString(expected);
        String persistStr = ctx.yamlMapper().writeValueAsString(persistResult);
        assertThat(persistStr).isEqualTo(expectedStr);
    }
}
