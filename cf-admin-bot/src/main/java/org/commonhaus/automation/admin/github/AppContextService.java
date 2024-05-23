package org.commonhaus.automation.admin.github;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import org.commonhaus.automation.admin.AdminConfig;
import org.commonhaus.automation.admin.AdminConfig.AttestationConfig;
import org.commonhaus.automation.admin.RepositoryConfigFile;
import org.commonhaus.automation.config.BotConfig;
import org.commonhaus.automation.github.context.BaseContextService;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.DumperOptions.FlowStyle;
import org.yaml.snakeyaml.DumperOptions.ScalarStyle;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.VisibilityChecker;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactoryBuilder;

import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkiverse.githubapp.GitHubConfigFileProvider;
import io.quarkus.logging.Log;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.runtime.annotations.ConfigItem;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.mutiny.core.eventbus.EventBus;

@Singleton
public class AppContextService extends BaseContextService {
    AdminQueryContext adminQueryContext = null;

    private static final Long DISABLED = -1L;

    private static ObjectMapper yamlMapper;

    final AdminConfig adminData;

    final List<String> attestationIds = new ArrayList<>();

    @ConfigItem(defaultValue = "${quarkus.github-app.instance-endpoint}/graphql")
    String graphqlApiEndpoint;

    long dataStoreInstallationId = DISABLED;

    public AppContextService(BotConfig data, AdminConfig adminData,
            GitHubClientProvider gitHubClientProvider,
            GitHubConfigFileProvider configProvider, EventBus bus) {
        super(data, gitHubClientProvider, configProvider, bus);
        this.adminData = adminData;
    }

    public AdminQueryContext newRepoAdminQueryContext(GHRepository repository, MonitoredRepo repoCfg) {
        return new AdminQueryContext(this, repository, repoCfg);
    }

    public AdminQueryContext newAdminQueryContext(GitHub github, GHRepository repository, long installationId) {
        return new AdminQueryContext(this, repository, installationId)
                .addExisting(github);
    }

    /**
     * Event filter: check if the push event contains changes to the specified path
     *
     * @param pushEvent the push event
     * @param path the path to check
     * @return true if the push event contains changes to the path
     */
    public boolean commitsContain(GHEventPayload.Push pushEvent, String path) {
        return pushEvent.getCommits().stream()
                .anyMatch(commit -> commit.getAdded().contains(path)
                        || commit.getModified().contains(path));
    }

    /**
     * Create an AdminQueryContext based on the known datastore repository
     * and discovered installation id.
     */
    public AdminQueryContext getAdminQueryContext() {
        if (adminData.dataStore() != null && dataStoreInstallationId != DISABLED) {
            AdminQueryContext qc = adminQueryContext;
            if (qc == null || qc.checkExpiredConnection()) {
                try {
                    GitHub github = getInstallationClient(dataStoreInstallationId);
                    GHRepository repository = github.getRepository(adminData.dataStore());
                    qc = adminQueryContext = new AdminQueryContext(this, repository, dataStoreInstallationId)
                            .addExisting(github);
                } catch (IOException e) {
                    logAndSendEmail("getAdminQueryContext",
                            "Unable to find repository %s for installation %s".formatted(adminData.dataStore(),
                                    dataStoreInstallationId),
                            e, botErrorEmailAddress());
                }
            }
            return qc;
        }
        return null;
    }

    /**
     * Event handler for repository discovery.
     * If the discovered repo matches the configured data store,
     * remember the installation id.
     */
    @Override
    protected void repositoryDiscovered(@Observes RepositoryDiscoveryEvent repoEvent) {
        super.repositoryDiscovered(repoEvent);

        GHRepository repo = repoEvent.repository();
        String repoFullName = repo.getFullName();
        String dataStore = getDataStore();
        String attestationRepo = adminData.attestations().repo();

        if (Objects.equals(repoFullName, dataStore)) {
            if (repoEvent.removed()) {
                dataStoreInstallationId = DISABLED;
            } else {
                dataStoreInstallationId = repoEvent.installationId();
                AdminQueryContext qc = adminQueryContext;
                if (qc == null) {
                    qc = adminQueryContext = newAdminQueryContext(
                            repoEvent.github(),
                            repo,
                            dataStoreInstallationId);
                }
            }
        }
        if (Objects.equals(repoFullName, attestationRepo)) {
            AdminQueryContext qc = newAdminQueryContext(
                    repoEvent.github(),
                    repo,
                    repoEvent.installationId());
            updateValidAttestations(qc);
        }
    }

    public void updateValidAttestations(AdminQueryContext qc) {
        JsonNode agreements = qc.readSourceFile(qc.getRepository(), adminData.attestations().path());
        if (agreements != null) {
            attestationIds.clear();
            JsonNode attestations = agreements.get("attestations");
            if (attestations != null && attestations.isObject()) {
                attestations.fields().forEachRemaining(entry -> {
                    attestationIds.add(entry.getKey());
                });
            }
        }
    }

    public Class<RepositoryConfigFile> getConfigType() {
        return RepositoryConfigFile.class;
    }

    public String getConfigFileName() {
        return RepositoryConfigFile.NAME;
    }

    public String getDataStore() {
        return adminData.dataStore();
    }

    public URI getMemberHome() {
        return adminData.member().home();
    }

    public String getGraphQlUrl() {
        return graphqlApiEndpoint;
    }

    public boolean validAttestation(String id) {
        // If none are defined/found, anything goes
        return attestationIds.isEmpty() || attestationIds.contains(id);
    }

    public static ObjectMapper yamlMapper() {
        if (yamlMapper == null) {
            DumperOptions options = new DumperOptions();
            options.setDefaultScalarStyle(ScalarStyle.PLAIN);
            options.setDefaultFlowStyle(FlowStyle.AUTO);
            options.setPrettyFlow(true);

            yamlMapper = new ObjectMapper(new YAMLFactoryBuilder(new YAMLFactory())
                    .dumperOptions(options).build())
                    .setSerializationInclusion(Include.NON_EMPTY)
                    .setVisibility(VisibilityChecker.Std.defaultInstance()
                            .with(JsonAutoDetect.Visibility.ANY));
        }
        return yamlMapper;
    }

    public GitHub getConnection(String nodeId, SecurityIdentity identity) {
        GitHub connect = AdminDataCache.USER_CONNECTION.get(nodeId);

        if (connect == null || !connect.isCredentialValid()) {
            try {
                AccessTokenCredential token = identity.getCredential(AccessTokenCredential.class);
                connect = new GitHubBuilder().withOAuthToken(token.getToken(), nodeId).build();
                AdminDataCache.USER_CONNECTION.put(nodeId, connect);
            } catch (IOException e) {
                Log.errorf(e, "%s failed to create session", nodeId);
                connect = null;
            }
        }
        return connect;
    }

    public AttestationConfig attestationConfig() {
        return adminData.attestations();
    }

    public boolean userIsKnown(String login) {
        return AdminDataCache.KNOWN_USER.computeIfAbsent(login, (k) -> {
            AdminQueryContext qc = getAdminQueryContext();
            return qc != null && qc.userIsKnown(login, adminData.member());
        });
    }
}
