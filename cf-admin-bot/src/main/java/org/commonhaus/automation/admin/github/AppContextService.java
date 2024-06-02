package org.commonhaus.automation.admin.github;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import org.commonhaus.automation.admin.AdminConfig;
import org.commonhaus.automation.admin.AdminConfig.AttestationConfig;
import org.commonhaus.automation.admin.AdminConfig.MemberConfig;
import org.commonhaus.automation.admin.AdminDataCache;
import org.commonhaus.automation.admin.RepositoryConfigFile;
import org.commonhaus.automation.admin.api.CommonhausUser.MemberStatus;
import org.commonhaus.automation.admin.forwardemail.Alias;
import org.commonhaus.automation.admin.forwardemail.ForwardEmailClient;
import org.commonhaus.automation.config.BotConfig;
import org.commonhaus.automation.github.context.BaseContextService;
import org.commonhaus.automation.github.context.TeamList;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
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

    Map<String, ScopedQueryContext> scopedContexts = new ConcurrentHashMap<>();

    @RestClient
    ForwardEmailClient forwardEmailClient;

    private static ObjectMapper yamlMapper;

    final AdminConfig adminData;

    final Set<String> attestationIds = new HashSet<>();

    @ConfigItem(defaultValue = "${quarkus.github-app.instance-endpoint}/graphql")
    String graphqlApiEndpoint;

    public AppContextService(BotConfig data, AdminConfig adminData,
            GitHubClientProvider gitHubClientProvider,
            GitHubConfigFileProvider configProvider, EventBus bus) {
        super(data, gitHubClientProvider, configProvider, bus);
        this.adminData = adminData;
    }

    public ScopedQueryContext refreshScopedQueryContext(GHRepository repository, MonitoredRepo repoCfg) {
        ScopedQueryContext qc = new ScopedQueryContext(this, repository, repoCfg);
        scopedContexts.put(repository.getFullName(), qc);
        scopedContexts.put(toOrganizationName(repository.getFullName()), qc);
        scopedContexts.put("" + repoCfg.installationId(), qc);
        return qc;
    }

    public ScopedQueryContext refreshScopedQueryContext(GitHub github, GHRepository repository, long installationId) {
        ScopedQueryContext qc = new ScopedQueryContext(this, repository, installationId)
                .addExisting(github);
        scopedContexts.put(repository.getFullName(), qc);
        scopedContexts.put(toOrganizationName(repository.getFullName()), qc);
        scopedContexts.put("" + installationId, qc);
        return qc;
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

    public ScopedQueryContext getScopedQueryContext(String scope) {
        ScopedQueryContext qc = scopedContexts.get(scope);
        if (qc == null) {
            qc = scopedContexts.get(toOrganizationName(scope));
        }

        if (qc != null && qc.checkExpiredConnection()) {
            long ghId = qc.getInstallationId();
            String fullName = qc.getRepository().getFullName();

            Log.debugf("[%s] Creating new ScopedQueryContext for %s (%s)",
                    ghId, scope, fullName);

            try {
                GitHub github = getInstallationClient(qc.getInstallationId());
                github.getInstallation(); // authenticated installation

                GHRepository repository = github.getRepository(fullName);
                qc = new ScopedQueryContext(this, repository, ghId).addExisting(github);
            } catch (IOException e) {
                logAndSendEmail("getAdminQueryContext",
                        "Unable to find repository %s for installation %s".formatted(fullName, ghId),
                        e, botErrorEmailAddress());
            }
        }
        return qc;
    }

    public ScopedQueryContext getDatastoreContext() {
        return getScopedQueryContext(getDataStore());
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

        if (repoEvent.removed()) {
            scopedContexts.remove(repoFullName);

            scopedContexts.values().stream().filter(qc -> qc.getInstallationId() == repoEvent.installationId())
                    .findFirst().ifPresent(qc -> {
                        String name = toOrganizationName(qc.getRepository().getFullName());
                        scopedContexts.put("" + repoEvent.installationId(), qc);
                        scopedContexts.put(name, qc);
                    });
            return;
        }

        ScopedQueryContext qc = refreshScopedQueryContext(
                repoEvent.github(),
                repo,
                repoEvent.installationId());

        if (Objects.equals(repoFullName, attestationRepo)) {
            updateValidAttestations(qc);
        }
    }

    public void updateValidAttestations(ScopedQueryContext qc) {
        JsonNode agreements = qc.readSourceFile(qc.getRepository(), adminData.attestations().path());
        if (agreements != null) {
            List<String> newIds = new ArrayList<>();
            JsonNode attestations = agreements.get("attestations");
            if (attestations != null && attestations.isObject()) {
                attestations.fields().forEachRemaining(entry -> {
                    newIds.add(entry.getKey());
                });
            }
            attestationIds.addAll(newIds);
            attestationIds.retainAll(newIds);
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
        Boolean result = AdminDataCache.KNOWN_USER.get(login);
        if (result != null) {
            return result;
        }

        MemberConfig memberConfig = adminData.member();
        GHUser ghUser = getDatastoreContext().getUser(login);
        if (memberConfig == null || ghUser == null) {
            // do not cache this case
            return false;
        }

        if (memberConfig.collaborators().isPresent()) {
            Log.debugf("collaborators: %s", memberConfig.collaborators().get());
            for (String repoName : memberConfig.collaborators().get()) {
                ScopedQueryContext qc = getScopedQueryContext(repoName);
                if (qc == null) {
                    Log.errorf("No context for %s", repoName);
                } else {
                    Set<String> names = qc.execGitHubSync((gh, dryRun) -> {
                        GHRepository repo = gh.getRepository(repoName);
                        return repo == null
                                ? null
                                : repo.getCollaboratorNames();
                    });
                    qc.clearNotFound();
                    if (names != null && names.contains(login)) {
                        result = Boolean.TRUE;
                        break;
                    }
                }
            }
        }
        if (result == null && memberConfig.organizations().isPresent()) {
            for (String orgName : memberConfig.organizations().get()) {
                ScopedQueryContext qc = getScopedQueryContext(orgName);
                GHOrganization org = qc.getOrganization(orgName);
                result = qc.execGitHubSync((gh, dryRun) -> {
                    return org == null
                            ? null
                            : org.hasMember(ghUser);
                });
                if (result != null) {
                    break;
                }
            }
        }

        if (result != null) {
            AdminDataCache.KNOWN_USER.put(login, result);
            return result;
        }
        return false;
    }

    public boolean userInTeam(String login, String fullTeamName) {
        String orgName = toOrganizationName(fullTeamName);
        ScopedQueryContext qc = getScopedQueryContext(orgName);

        GHOrganization org = qc.getOrganization(orgName);
        TeamList teamList = qc.getTeamList(org, fullTeamName);
        return teamList != null && teamList.hasLogin(login);
    }

    public Map<String, Alias> getAliases(List<String> emails, boolean resetCache) {
        Map<String, Alias> aliases = new HashMap<>();
        for (String email : emails) {
            Alias alias = getAlias(email, resetCache);
            if (alias != null) {
                aliases.put(getKey(alias), alias);
            }
        }
        return aliases;
    }

    public Map<String, Alias> setRecipients(String description, Map<String, Set<String>> aliases) {
        Map<String, Alias> result = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : aliases.entrySet()) {
            Alias updated = putAlias(description, entry.getKey(), entry.getValue());
            if (updated != null) {
                result.put(getKey(updated), updated);
            }
        }
        return result;
    }

    public boolean generatePassword(String email) {
        Alias alias = getAlias(email, false);
        // TODO: NOT YET.. SOOOON
        // if (alias != null && alias.verified_recipients != null && alias.verified_recipients.size() > 0) {
        //     forwardEmailClient.generatePassword(alias.domain.name, alias.id, alias.verified_recipients.iterator().next());
        //     return true;
        // }
        return false;
    }

    private String getKey(Alias alias) {
        return alias.name + "@" + alias.domain.name;
    }

    private Alias getAlias(String email, boolean resetCache) {
        int at = email.indexOf('@');
        String domain = at < 0 ? adminData.defaultAliasDomain() : email.substring(at + 1);
        String name = at < 0 ? email : email.substring(0, at);

        Alias alias = resetCache ? null : AdminDataCache.ALIASES.get(email);
        if (alias == null) {
            alias = forwardEmailClient.getAlias(domain, name);
            if (alias != null) {
                AdminDataCache.ALIASES.put(email, alias);
            }
        }
        return alias;
    }

    private Alias putAlias(String description, String email, Set<String> recipients) {
        if (recipients == null || recipients.isEmpty()) {
            return null;
        }
        int at = email.indexOf('@');
        String name = email.substring(0, at);
        String domain = at < 0 ? adminData.defaultAliasDomain() : email.substring(at + 1);

        Alias alias = getAlias(email, false);
        if (alias == null) {
            alias = new Alias();
            alias.name = name;
            alias.description = description;
            alias.recipients = recipients;
            alias.is_enabled = true;
            alias.has_recipient_verification = true;
            forwardEmailClient.createAlias(domain, alias);
        } else if (!Objects.equals(alias.recipients, recipients) || !Objects.equals(alias.description, description)) {
            alias.has_recipient_verification = true;
            alias.description = description;
            alias.recipients = recipients;
            if (alias.verified_recipients != null) {
                alias.verified_recipients.retainAll(recipients);
            }
            forwardEmailClient.updateAlias(domain, alias.id, alias);
        }
        AdminDataCache.ALIASES.put(email, alias);
        return alias;
    }

    public Iterable<Entry<String, String>> groupRole() {
        return adminData.groupRole().entrySet();
    }

    public MemberStatus getStatusForRole(String role) {
        String status = adminData.roleStatus().get(role);
        return MemberStatus.fromString(status);
    }

    public String getDefaultDomain() {
        return adminData.defaultAliasDomain();
    }

    public String toOrganizationName(String fullName) {
        int pos = fullName.indexOf('/');
        return pos < 0 ? fullName : fullName.substring(0, pos);
    }
}
