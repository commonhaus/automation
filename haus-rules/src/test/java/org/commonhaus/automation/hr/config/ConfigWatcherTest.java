package org.commonhaus.automation.hr.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import jakarta.inject.Inject;

import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdateType;
import org.commonhaus.automation.hr.HausRulesTestBase;
import org.commonhaus.automation.hr.voting.VoteInformation.Alternates;
import org.commonhaus.automation.hr.voting.VoteQueryCache;
import org.junit.jupiter.api.Test;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
class ConfigWatcherTest extends HausRulesTestBase {

    @Inject
    ConfigWatcher configWatcher;

    @Test
    void configRefreshInvalidatesAlternateCache() throws Exception {
        setupDefaultMocks(TEST_ORG);
        String key = VoteQueryCache.alternateCacheKey(repositoryId);
        VoteQueryCache.ALT_ACTORS.put(key, new Alternates(Map.of()));
        mockFileContent(hausMocks.repository(), HausRulesConfig.PATH, "src/test/resources/cf-voting.yml");

        configWatcher.readConfiguration(installationId, hausMocks.repository(), FileUpdateType.MODIFIED);

        Alternates cached = VoteQueryCache.ALT_ACTORS.get(key);
        assertThat(cached).isNull();
    }

    @Test
    void configRemovalInvalidatesAlternateCache() throws Exception {
        setupDefaultMocks(TEST_ORG);
        String key = VoteQueryCache.alternateCacheKey(repositoryId);
        VoteQueryCache.ALT_ACTORS.put(key, new Alternates(Map.of()));

        configWatcher.readConfiguration(installationId, hausMocks.repository(), FileUpdateType.REMOVED);

        Alternates cached = VoteQueryCache.ALT_ACTORS.get(key);
        assertThat(cached).isNull();
    }
}
