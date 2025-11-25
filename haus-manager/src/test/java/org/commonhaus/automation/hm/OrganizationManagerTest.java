package org.commonhaus.automation.hm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdate;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdateType;
import org.commonhaus.automation.hm.config.OrganizationConfig;
import org.commonhaus.automation.hm.github.HausManagerTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHRepository;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class OrganizationManagerTest extends HausManagerTestBase {

    @Inject
    OrganizationManager organizationManager;

    GHRepository contactRepo;

    @BeforeEach
    @Override
    protected void setup() throws IOException {
        super.setup();
        Log.info("START: OrganizationManagerTest.setup()");

        // Mock the file content for organization config in primary repo
        mockFileContent(hausMocks, OrganizationConfig.PATH,
                "src/test/resources/cf-haus-organization.yml");

        // Mock the file content for CONTACTS.yaml in alt repo (public content)
        contactRepo = mockRepository("public-org/source", hausMocks.github());
        mockFileContent(contactRepo, "CONTACTS.yaml",
                "src/test/resources/test-contacts.yml");

        mockTeam("test-org/cf-council", null);
        mockTeam("test-org/admin", null);
        mockTeam("test-org/team-quorum", null);
    }

    @AfterEach
    void cleaup() {
        // Reset/cleanup the organization manager
        organizationManager.reset();
    }

    @Test
    void testRepositoryEvents() throws IOException {
        // Trigger discovery to initialize manager
        triggerRepositoryDiscovery(DiscoveryAction.ADDED, hausMocks, true);

        // Trigger bootstrap completion to execute deferred reconciliation
        triggerBootstrapDiscovery(hausMocks);

        waitForQueue();

        OrganizationConfig config = organizationManager.getConfig();
        assertThat(config).isNotNull();

        List<String> teamNames = teams(config);
        System.out.println("Config teams: " + teamNames);

        assertThat(teamNames).isNotEmpty();

        assertThat(config.domainManagement()).isNotNull();
        assertThat(config.domainManagement().enabled()).isFalse();
        assertThat(config.domainManagement().dryRun()).isTrue();

        assertThat(config.githubOrgVerification()).isNotNull();
        assertThat(config.githubOrgVerification().dryRun()).isTrue();

        assertThat(config.sponsors()).isNotNull();
        assertThat(config.sponsors().enabled()).isFalse();

        // Project assets

        assertThat(config.projects()).isNotNull();

        var expectedDomains = config.projects().expectedDomains();
        assertThat(expectedDomains).isNotEmpty();
        assertThat(expectedDomains).hasSize(3);

        var expectedOrgs = config.projects().expectedOrganizations();
        assertThat(expectedOrgs).isNotEmpty();
        assertThat(expectedOrgs).hasSize(2);

        // Trigger config removal

        triggerRepositoryDiscovery(DiscoveryAction.REMOVED, hausMocks, false);

        waitForQueue();

        assertThat(organizationManager.getConfig()).isNull();

        // Verify team cache was refreshed
        verify(contactRepo, times(1)).getFileContent(anyString());

        // Verify team sync was called for each target team
        verify(teamService, times(1)).syncMembers(any(), eq("test-org/cf-council"), any(), any(), anyBoolean(), any());
        verify(teamService, times(1)).syncMembers(any(), eq("test-org/admin"), any(), any(), anyBoolean(), any());
        verify(teamService, times(1)).syncMembers(any(), eq("test-org/team-quorum"), any(), any(), anyBoolean(), any());
    }

    @Test
    void testConfigurationUpdated() throws IOException {
        organizationManager.processFileUpdate(new FileUpdate(
                OrganizationConfig.PATH, FileUpdateType.MODIFIED,
                hausMocks.installationId(), hausMocks.repository(), hausMocks.github()));

        // Verify config was read
        waitForQueue();

        OrganizationConfig config = organizationManager.getConfig();
        assertThat(config).isNotNull();
        List<String> teamNames = teams(config);
        System.out.println("Config teams: " + teamNames);
        assertThat(teamNames).isNotEmpty();

        // Verify team cache was refreshed
        verify(contactRepo, timeout(1000)).getFileContent(anyString());

        // Verify team sync was called for each target team
        verify(teamService, timeout(1000)).syncMembers(any(), eq("test-org/cf-council"), any(), any(), anyBoolean(), any());
        verify(teamService, timeout(1000)).syncMembers(any(), eq("test-org/admin"), any(), any(), anyBoolean(), any());
        verify(teamService, timeout(1000)).syncMembers(any(), eq("test-org/team-quorum"), any(), any(), anyBoolean(), any());
    }

    List<String> teams(OrganizationConfig config) {
        return config.teamMembership().stream()
                .flatMap(x -> x.watchedTeams("org").stream())
                .collect(Collectors.toList());
    }
}
