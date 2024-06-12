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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.admin.AdminConfig;
import org.commonhaus.automation.admin.AdminDataCache;
import org.commonhaus.automation.admin.api.CommonhausUser.MemberStatus;
import org.commonhaus.automation.admin.api.MemberSession;
import org.commonhaus.automation.admin.config.AdminConfigFile;
import org.commonhaus.automation.admin.config.TeamManagementConfig;
import org.commonhaus.automation.admin.config.UserManagementConfig;
import org.commonhaus.automation.admin.config.UserManagementConfig.AttestationConfig;
import org.commonhaus.automation.admin.forwardemail.Alias;
import org.commonhaus.automation.admin.forwardemail.ForwardEmailClient;
import org.commonhaus.automation.config.BotConfig;
import org.commonhaus.automation.github.context.BaseContextService;
import org.commonhaus.automation.github.context.BaseQueryCache;
import org.commonhaus.automation.github.context.QueryContext;
import org.commonhaus.automation.github.discovery.DiscoveryAction;
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
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactoryBuilder;

import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkiverse.githubapp.GitHubConfigFileProvider;
import io.quarkus.logging.Log;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.mutiny.core.eventbus.EventBus;

@Singleton
public class AppContextService extends BaseContextService {

    // This all feels ridiculous. But we need to find the right installation
    // for the access we need across multiple installations to construct
    // query contexts with the necessary permissions to modify team membership
    // or manage membership-related issues/user records.
    final Map<Long, InstallationAccess> installationAccess = new ConcurrentHashMap<>();
    final Map<String, Long> writeToInstallId = new ConcurrentHashMap<>();
    final Map<String, Long> readToInstallId = new ConcurrentHashMap<>();

    @RestClient
    ForwardEmailClient forwardEmailClient;

    UserManagementConfig userConfig = UserManagementConfig.DISABLED;

    private static ObjectMapper yamlMapper;

    final AdminConfig adminData;

    final Set<String> attestationIds = new HashSet<>();

    public AppContextService(BotConfig data, AdminConfig adminData,
            GitHubClientProvider gitHubClientProvider,
            GitHubConfigFileProvider configProvider, EventBus bus) {
        super(data, gitHubClientProvider, configProvider, bus);
        this.adminData = adminData;
    }

    public ScopedQueryContext refreshScopedQueryContext(@Nonnull MonitoredRepo repoCfg, GHRepository repository) {
        return new ScopedQueryContext(this, repoCfg.installationId(), repository)
                .addExisting(repoCfg);
    }

    public ScopedQueryContext refreshScopedQueryContext(long installationId, GHRepository repository) {
        return new ScopedQueryContext(this, installationId, repository);
    }

    public ScopedQueryContext refreshScopedQueryContext(long installationId, GHOrganization org, GHRepository repository) {
        return new ScopedQueryContext(this, installationId, org, repository);
    }

    public ScopedQueryContext getDatastoreContext() {
        return getScopedQueryContext(getDataStore());
    }

    public ScopedQueryContext getScopedQueryContext(String scope) {
        String orgName = QueryContext.toOrganizationName(scope);

        // try for write access first: full scope, then just the org
        Long installationId = writeToInstallId.getOrDefault(scope,
                writeToInstallId.get(orgName));
        if (installationId == null) {
            // if we didn't find that, look for read access: full scope, then just the org
            installationId = readToInstallId.getOrDefault(scope,
                    readToInstallId.get(orgName));
        }
        if (installationId == null) {
            Log.errorf("No installation found for %s", scope);
            return null;
        }
        InstallationAccess access = installationAccess.get(installationId);
        return access.containsRepo(scope)
                ? new ScopedQueryContext(this, installationId, orgName, scope)
                : new ScopedQueryContext(this, installationId, orgName, null);
    }

    public UserQueryContext newUserQueryContext(MemberSession memberSession) {
        return new UserQueryContext(this, memberSession);
    }

    /**
     * Event handler for repository discovery.
     * If the discovered repo matches the configured data store,
     * remember the installation id.
     */
    @Override
    protected void repositoryDiscovered(@Observes RepositoryDiscoveryEvent repoEvent) {
        super.repositoryDiscovered(repoEvent);

        DiscoveryAction action = repoEvent.action();
        long installationId = repoEvent.installationId();
        String repoFullName = repoEvent.repository().getFullName();
        String orgName = ScopedQueryContext.toOrganizationName(repoFullName);
        Optional<AdminConfigFile> repoConfig = repoEvent.getRepositoryConfig();

        if (action.added()) {
            InstallationAccess access = installationAccess.computeIfAbsent(installationId,
                    InstallationAccess::new);
            access.write.add(repoFullName);
            access.write.add(orgName);

            UserManagementConfig userConfig = UserManagementConfig.getUserManagementConfig(repoConfig.orElse(null));
            if (userConfig != null && !userConfig.isDisabled()) {
                this.userConfig = userConfig;
            }
            TeamManagementConfig teamConfig = TeamManagementConfig.getGroupManagementConfig(repoConfig.orElse(null));
            if (teamConfig != null && !teamConfig.isDisabled()) {
                teamConfig.setAccess(access);
            }

            // update indexes
            access.write.forEach(s -> writeToInstallId.put(s, access.installationId));
            access.read.forEach(s -> readToInstallId.put(s, access.installationId));
        } else if (action.removed()) {
            if (repoFullName.equals(getDataStore())) {
                userConfig = UserManagementConfig.DISABLED;
            }

            InstallationAccess access = installationAccess.get(installationId);
            if (action.installation()) {
                // Installation is removed, forget all access
                access = installationAccess.remove(installationId);
                if (access != null) {
                    // repair or replace values first
                    installationAccess.values().forEach(remaining -> {
                        remaining.write.forEach(s -> writeToInstallId.put(s, remaining.installationId));
                        remaining.read.forEach(s -> readToInstallId.put(s, remaining.installationId));
                    });

                    // now clean up anything that still points to the installation being removed
                    writeToInstallId.values().removeIf(x -> x == installationId);
                    readToInstallId.values().removeIf(x -> x == installationId);
                }
            } else {
                // Just one repo. Remove write access, and read access if private
                access.write.remove(repoFullName);
                if (repoEvent.repository().isPrivate()) {
                    access.read.remove(repoFullName);
                }
            }
        }
    }

    /**
     * Create or renew a GitHub connection for a user.
     * May return null if connection can not be established
     *
     * @param nodeId User Node ID
     * @param identity Security Identity
     * @return GitHub connection or null
     * @throws IOException
     */
    public GitHub getUserConnection(String nodeId, SecurityIdentity identity) throws IOException {
        GitHub connect = BaseQueryCache.CONNECTION.get("user-" + nodeId);
        if (connect == null || !connect.isCredentialValid()) {
            AccessTokenCredential token = identity.getCredential(AccessTokenCredential.class);
            connect = new GitHubBuilder().withOAuthToken(token.getToken(), nodeId).build();
            BaseQueryCache.CONNECTION.put("user-" + nodeId, connect);
        }
        return connect;
    }

    public void updateUserConnection(String nodeId, GitHub gh) {
        BaseQueryCache.CONNECTION.put("user-" + nodeId, gh);
    }

    public Class<AdminConfigFile> getConfigType() {
        return AdminConfigFile.class;
    }

    public String getConfigFileName() {
        return AdminConfigFile.NAME;
    }

    public String getDataStore() {
        return adminData.dataStore();
    }

    public URI getMemberHome() {
        return adminData.memberHome();
    }

    public void updateValidAttestations(ScopedQueryContext qc) {
        if (userConfig.isDisabled()) {
            return;
        }
        JsonNode agreements = qc.readSourceFile(qc.getRepository(), userConfig.attestations().path());
        if (agreements != null) {
            List<String> newIds = new ArrayList<>();
            JsonNode attestations = agreements.get("attestations");
            if (attestations != null && attestations.isObject()) {
                attestations.fields().forEachRemaining(entry -> newIds.add(entry.getKey()));
            }
            attestationIds.addAll(newIds);
            attestationIds.retainAll(newIds);
        }
    }

    public boolean validAttestation(String id) {
        // If none are defined/found, anything goes
        return attestationIds.isEmpty() || attestationIds.contains(id);
    }

    public AttestationConfig attestationConfig() {
        if (userConfig.isDisabled()) {
            return null;
        }
        return userConfig.attestations();
    }

    public boolean userIsKnown(QueryContext initQc, String login, Set<String> roles) {
        if (userConfig.isDisabled()) {
            return false;
        }

        Boolean result = AdminDataCache.KNOWN_USER.get(login);
        if (result != null) {
            return result;
        }

        GHUser ghUser = initQc.getUser(login);
        if (ghUser == null) {
            // do not cache this case
            return false;
        }

        result = Boolean.FALSE;
        if (!userConfig.collaboratorRoles().isEmpty()) {
            Map<String, String> collabRoles = userConfig.collaboratorRoles();
            Log.debugf("collaborators: %s", collabRoles);

            for (Entry<String, String> entry : collabRoles.entrySet()) {
                String repoName = entry.getKey();
                String role = entry.getValue();

                ScopedQueryContext qc = getScopedQueryContext(repoName);
                if (qc == null) {
                    Log.errorf("No context for %s", repoName);
                } else {
                    if (qc.isCollaborator(ghUser, repoName)) {
                        roles.add(role);
                        result = or(result, Boolean.TRUE);
                    }
                }
            }
        }
        if (!userConfig.teamRoles().isEmpty()) {
            Map<String, String> teamRoles = userConfig.teamRoles();
            Log.debugf("teamRoles: %s", teamRoles);

            for (Entry<String, String> entry : teamRoles.entrySet()) {
                String teamFullName = entry.getKey();
                String role = entry.getValue();

                String orgName = ScopedQueryContext.toOrganizationName(teamFullName);
                ScopedQueryContext qc = getScopedQueryContext(orgName);

                if (qc == null) {
                    Log.errorf("No context for %s", orgName);
                } else if (qc.isTeamMember(ghUser, teamFullName)) {
                    roles.add(role);
                    result = or(result, Boolean.TRUE);
                }
            }
        }
        AdminDataCache.KNOWN_USER.put(login, result);
        return result;
    }

    Boolean or(Boolean a, Boolean b) {
        return Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b);
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
        if (userConfig.isDisabled()) {
            return false;
        }
        Alias alias = getAlias(email, false);
        // TODO: NOT YET.. SOOOON
        // if (alias != null && alias.verified_recipients != null &&
        // alias.verified_recipients.size() > 0) {
        // forwardEmailClient.generatePassword(alias.domain.name, alias.id,
        // alias.verified_recipients.iterator().next());
        // return true;
        // }
        return false;
    }

    private String getKey(Alias alias) {
        return alias.name + "@" + alias.domain.name;
    }

    private Alias getAlias(String email, boolean resetCache) {
        if (userConfig.emailDisabled()) {
            return null;
        }
        int at = email.indexOf('@');
        String domain = at < 0 ? userConfig.defaultAliasDomain() : email.substring(at + 1);
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
        if (userConfig.emailDisabled() || recipients == null || recipients.isEmpty()) {
            return null;
        }
        int at = email.indexOf('@');
        String name = at < 0 ? email : email.substring(0, at);
        String domain = at < 0 ? userConfig.defaultAliasDomain() : email.substring(at + 1);

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

    public MemberStatus getStatusForRole(String role) {
        if (userConfig.isDisabled()) {
            return MemberStatus.UNKNOWN;
        }
        String status = userConfig.roleStatus().get(role);
        return MemberStatus.fromString(status);
    }

    public String getTeamForRole(String role) {
        if (userConfig.isDisabled()) {
            return null;
        }
        return userConfig.teamRoles().entrySet().stream()
                .filter(entry -> entry.getValue().equals(role))
                .map(Entry::getKey)
                .findFirst()
                .orElse(null);
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

    public Response toResponse(String logId, String message, Throwable t) {
        if (t.toString().toLowerCase().contains("timeout")) { // totally cheating
            return Response.status(Response.Status.GATEWAY_TIMEOUT).build();
        }
        Log.errorf(t, "[%s] %s; %s", logId, message, t);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }

    public Response toResponseWithEmail(String logId, String message, Throwable t) {
        if (t.toString().toLowerCase().contains("timeout")) { // totally cheating
            return Response.status(Response.Status.GATEWAY_TIMEOUT).build();
        }
        logAndSendEmail(logId, message, t, null);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }

    public static ObjectMapper yamlMapper() {
        if (yamlMapper == null) {
            DumperOptions options = new DumperOptions();
            options.setDefaultScalarStyle(ScalarStyle.PLAIN);
            options.setDefaultFlowStyle(FlowStyle.AUTO);
            options.setPrettyFlow(true);

            yamlMapper = new ObjectMapper(new YAMLFactoryBuilder(new YAMLFactory())
                    .dumperOptions(options).build())
                    .findAndRegisterModules()
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .setSerializationInclusion(Include.NON_EMPTY)
                    .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.NON_PRIVATE);
        }
        return yamlMapper;
    }
}
