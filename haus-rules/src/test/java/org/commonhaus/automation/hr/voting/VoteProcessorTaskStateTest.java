package org.commonhaus.automation.hr.voting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;

import jakarta.inject.Inject;

import org.commonhaus.automation.ContextService;
import org.commonhaus.automation.github.context.ContextHelper;
import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.commonhaus.automation.hr.HausRulesTestBase;
import org.commonhaus.automation.hr.config.ConfigWatcher;
import org.commonhaus.automation.hr.config.HausRulesConfig;
import org.commonhaus.automation.hr.config.VoteConfig;
import org.commonhaus.automation.queue.PeriodicUpdateQueue;
import org.commonhaus.automation.queue.ScheduledService;
import org.commonhaus.automation.queue.TaskStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
class VoteProcessorTaskStateTest extends HausRulesTestBase {

    @Inject
    ConfigWatcher configWatcher;

    @BeforeEach
    void setupProcessor() throws Exception {
        setupDefaultMocks(TEST_ORG);
    }

    @Test
    void discoverVotesRecordsRunWhenScheduledWorkIsQueued() throws Exception {
        TestVoteProcessor processor = newProcessor();
        configWatcher.updateVoteConfig(repoFullName, readVotingConfig());
        processor.instance.repositoryDiscovered(repositoryAddedEvent());
        clearLastRun(processor.instance);

        processor.instance.discoverVotes(false);

        verify(processor.taskState).shouldRun("🗳️ ", Duration.ofHours(1));
        verify(processor.taskState).recordRun("🗳️ ");
        verify(processor.periodicUpdate).queue(eq(repoFullName), any(Runnable.class));
        assertThat(getLastRun(processor.instance)).isNotEqualTo("never");
    }

    @Test
    void discoverVotesRecordsRunWhenNoEnabledRepositoriesRemain() throws Exception {
        TestVoteProcessor processor = newProcessor();
        configWatcher.updateVoteConfig(repoFullName, readVotingConfig());
        processor.instance.repositoryDiscovered(repositoryAddedEvent());
        configWatcher.updateConfig(repoFullName, new HausRulesConfig(null, VoteConfig.DISABLED));
        clearLastRun(processor.instance);

        processor.instance.discoverVotes(false);

        verify(processor.taskState).shouldRun("🗳️ ", Duration.ofHours(1));
        verify(processor.taskState).recordRun("🗳️ ");
        verify(processor.periodicUpdate, never()).queue(eq(repoFullName), any(Runnable.class));
        assertThat(getLastRun(processor.instance)).isNotEqualTo("never");
    }

    @Test
    void discoverVotesBypassesShouldRunWhenUserTriggered() throws Exception {
        TestVoteProcessor processor = newProcessor();
        configWatcher.updateVoteConfig(repoFullName, readVotingConfig());
        processor.instance.repositoryDiscovered(repositoryAddedEvent());
        clearLastRun(processor.instance);

        processor.instance.discoverVotes(true);

        verify(processor.taskState, never()).shouldRun("🗳️ ", Duration.ofHours(1));
        verify(processor.taskState).recordRun("🗳️ ");
        verify(processor.periodicUpdate).queue(eq(repoFullName), any(Runnable.class));
        assertThat(getLastRun(processor.instance)).isNotEqualTo("never");
    }

    private HausRulesConfig readVotingConfig() throws Exception {
        return ContextService.yamlMapper.readValue(
                ContextHelper.class.getResourceAsStream("/cf-voting.yml"),
                HausRulesConfig.class);
    }

    private RepositoryDiscoveryEvent repositoryAddedEvent() {
        return new RepositoryDiscoveryEvent(
                DiscoveryAction.ADDED,
                hausMocks.github(),
                hausMocks.dql(),
                installationId,
                hausMocks.repository(),
                false);
    }

    private static void clearLastRun(VoteProcessor processor) throws Exception {
        Field field = ScheduledService.class.getDeclaredField("lastRun");
        field.setAccessible(true);
        field.set(processor, "never");
    }

    private static String getLastRun(VoteProcessor processor) throws Exception {
        Field field = ScheduledService.class.getDeclaredField("lastRun");
        field.setAccessible(true);
        return (String) field.get(processor);
    }

    private TestVoteProcessor newProcessor() throws Exception {
        VoteProcessor processor = new VoteProcessor();
        processor.ctx = ctx;
        PeriodicUpdateQueue periodicUpdate = mock(PeriodicUpdateQueue.class);
        processor.periodicUpdate = periodicUpdate;

        TaskStateService taskState = mock(TaskStateService.class);
        when(taskState.shouldRun("🗳️ ", Duration.ofHours(1))).thenReturn(true);
        when(taskState.recordRun("🗳️ ")).thenReturn(Instant.parse("2026-08-24T12:00:00Z"));

        Field field = ScheduledService.class.getDeclaredField("taskState");
        field.setAccessible(true);
        field.set(processor, taskState);
        return new TestVoteProcessor(processor, periodicUpdate, taskState);
    }

    private static class TestVoteProcessor {
        final VoteProcessor instance;
        final PeriodicUpdateQueue periodicUpdate;
        final TaskStateService taskState;

        TestVoteProcessor(VoteProcessor instance, PeriodicUpdateQueue periodicUpdate, TaskStateService taskState) {
            this.instance = instance;
            this.periodicUpdate = periodicUpdate;
            this.taskState = taskState;
        }
    }
}
