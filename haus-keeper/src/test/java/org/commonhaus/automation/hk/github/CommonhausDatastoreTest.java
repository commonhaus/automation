package org.commonhaus.automation.hk.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.commonhaus.automation.hk.data.CommonhausUser;
import org.commonhaus.automation.hk.data.CommonhausUserData.Attestation;
import org.commonhaus.automation.hk.data.MemberStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHContentBuilder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class CommonhausDatastoreTest extends HausKeeperTestBase {
    @Inject
    HKTestBotConfig botConfig;

    @Inject
    CommonhausDatastore datastore;

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
        assertNoErrorEmails();
    }

    @Test
    void testEmptyJournal() throws Exception {
        datastore.initializeJournal();
        datastore.processJournalEntries();
        datastore.persistJournal();

        await().atLeast(10, TimeUnit.MILLISECONDS)
                .atMost(2, TimeUnit.SECONDS).until(() -> updateQueue.isEmpty());
    }

    @Test
    void testJournalNewUser() throws Exception {

        // Create user information and write into journal
        CommonhausUser user = CommonhausUser.create(botLogin, botId);
        user.setStatus(MemberStatus.PENDING);
        user.goodUntil().attestation().put("test", new Attestation(MemberStatus.PENDING, "2020-01-01", "test-only"));

        Path journalPath = botConfig.getTempJournalFile();
        ctx.yamlMapper().writeValue(journalPath.toFile(), Map.of(CommonhausDatastore.getKey(botLogin, botId), user));

        // Custom mocks: create new user
        GHContent content = mock(GHContent.class);
        GHContentBuilder builder = Mockito.mock(GHContentBuilder.class);
        mockUpdateCommonhausData(builder, UserPath.NEW_USER, content);

        // initialize journal: find pending user + create new records
        datastore.initializeJournal();
        datastore.processJournalEntries();

        // Allow queue to handle write
        await().atLeast(10, TimeUnit.MILLISECONDS)
                .atMost(2, TimeUnit.SECONDS).until(() -> updateQueue.isEmpty());

        datastore.persistJournal();

        // Verify persistence of user data + empty journal
        final ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(builder).path(pathCaptor.capture());
        assertThat(pathCaptor.getValue()).isEqualTo("data/users/156364140.yaml");

        final ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(builder).content(contentCaptor.capture());
        var persistResult = ctx.yamlMapper().readValue(contentCaptor.getValue(),
                CommonhausUser.class);

        String expectedStr = ctx.yamlMapper().writeValueAsString(user);
        String persistStr = ctx.yamlMapper().writeValueAsString(persistResult);
        assertThat(persistStr).isEqualTo(expectedStr);

        var journalData = ctx.yamlMapper().readValue(journalPath.toFile(), CommonhausDatastore.USER_JOURNAL_TYPE);
        assertThat(journalData).isEmpty();
    }
}
