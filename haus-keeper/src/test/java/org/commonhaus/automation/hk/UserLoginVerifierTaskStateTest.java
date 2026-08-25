package org.commonhaus.automation.hk;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import jakarta.inject.Inject;

import org.commonhaus.automation.hk.github.HausKeeperTestBase;
import org.commonhaus.automation.queue.TaskStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
class UserLoginVerifierTaskStateTest extends HausKeeperTestBase {

    @Inject
    UserLoginVerifier verifier;

    @InjectMock
    TaskStateService taskState;

    @BeforeEach
    void setupVerifier() throws IOException {
        setupInstallationRepositories();
        setupBotLogin();

        when(taskState.lastRunOrNow(anyString())).thenReturn(Instant.parse("2026-08-24T11:00:00Z"));
        when(taskState.recordRun(anyString())).thenReturn(Instant.parse("2026-08-24T12:00:00Z"));
        when(taskState.shouldRun(anyString(), any())).thenReturn(true);

        clearInvocations(taskState);
    }

    @Test
    void scheduledVerificationDoesNotRecordRunWhenDatastoreRepositoryIsMissing() throws Exception {
        when(dataMocks.github().getRepository(datastoreRepoName)).thenReturn(null);

        verifier.verifyAllUserLogins(false);

        verify(taskState).shouldRun("👍-login", Duration.ofHours(12));
        verify(taskState, never()).recordRun(anyString());
    }

    @Test
    void scheduledVerificationRecordsRunAfterDatastoreRepositoryIsAvailable() throws Exception {
        when(dataMocks.repository().readZip(any(), isNull()))
                .thenThrow(new RuntimeException("stop after recordRun"));

        verifier.verifyAllUserLogins(false);

        verify(taskState).recordRun("👍-login");
    }
}
