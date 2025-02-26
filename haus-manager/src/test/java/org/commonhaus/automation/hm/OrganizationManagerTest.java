package org.commonhaus.automation.hm;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Path;

import jakarta.inject.Inject;

import org.commonhaus.automation.github.context.ContextHelper;
import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.hm.config.OrganizationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
public class OrganizationManagerTest extends ContextHelper {

    static final DefaultValues PRIMARY = new DefaultValues(
            46053716,
            new Resource(144493209, "test-org"),
            new Resource("test-org/test-repo"));

    static final DefaultValues SECONDARY = new DefaultValues(
            46053716,
            new Resource(144493209, "other-org"),
            new Resource("other-org/other-repo"));

    @Inject
    TestManagerBotConfig configProducer;

    @Inject
    AppContextService appContextService;

    @Inject
    OrganizationManager organizationManager;

    MockInstallation primary;
    MockInstallation secondary;

    @BeforeEach
    void setup() throws IOException {
        primary = setupCommonMocks(PRIMARY);
        secondary = setupCommonMocks(SECONDARY);
    }

    @AfterEach
    void cleanup() {
        // Reset/cleanup the organization manager
        organizationManager.reset();
        Log.info("DONE: OrganizationManagerTest.cleanup()");
    }

    @Test
    void testConfigurationReading() throws IOException {
        // Setup mock repository with config file
        mockFileContent(primary, OrganizationConfig.PATH, Path.of("src/test/resources/cf-haus-organization.yml"));

        // Trigger discovery to initialize manager
        triggerRepositoryDiscovery(DiscoveryAction.ADDED, primary, true);

        // Verify config was read
        await().atMost(5, SECONDS).until(() -> organizationManager.getConfig() != null);
        OrganizationConfig config = organizationManager.getConfig();
        assertThat(config).isNotNull();
        assertThat(config.teamMembership().sync()).isNotEmpty();
    }

    @Test
    void testNext() throws IOException {
        System.out.println(organizationManager.toString());

    }
}
