package org.commonhaus.automation.admin.github;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import org.commonhaus.automation.admin.AdminConfig;
import org.commonhaus.automation.admin.AdminDataCache;
import org.commonhaus.automation.admin.api.ApplicationData;
import org.commonhaus.automation.admin.api.ApplicationData.ApplicationPost;
import org.commonhaus.automation.admin.api.ApplicationData.Feedback;
import org.commonhaus.automation.admin.api.CommonhausUser;
import org.commonhaus.automation.admin.api.CommonhausUser.MemberStatus;
import org.commonhaus.automation.admin.api.CommonhausUser.MembershipApplication;
import org.commonhaus.automation.admin.api.MemberSession;
import org.commonhaus.automation.admin.config.AdminConfigFile;
import org.commonhaus.automation.admin.config.UserManagementConfig;
import org.commonhaus.automation.admin.config.UserManagementConfig.AttestationConfig;
import org.commonhaus.automation.admin.forwardemail.Alias;
import org.commonhaus.automation.admin.forwardemail.ForwardEmailClient;
import org.commonhaus.automation.config.BotConfig;
import org.commonhaus.automation.github.context.BaseContextService;
import org.commonhaus.automation.github.context.DataCommonComment;
import org.commonhaus.automation.github.context.DataCommonItem;
import org.commonhaus.automation.github.context.DataLabel;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.github.context.QueryContext;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.kohsuke.github.GHEventPayload;
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

    @RestClient
    ForwardEmailClient forwardEmailClient;

    Map<String, ScopedQueryContext> scopedContexts = new ConcurrentHashMap<>();

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

    public ScopedQueryContext refreshScopedQueryContext(GHRepository repository, MonitoredRepo repoCfg, GitHub github) {
        ScopedQueryContext qc = new ScopedQueryContext(this, repository, repoCfg).addExisting(github);
        Log.debugf("[%s] Refresh ScopedQueryContext for monitored repository %s", repoCfg.installationId(),
                repository.getFullName());
        scopedContexts.put("" + repoCfg.installationId(), qc);
        scopedContexts.put(repository.getFullName(), qc);
        scopedContexts.put(ScopedQueryContext.toOrganizationName(repository.getFullName()), qc);
        return qc;
    }

    public ScopedQueryContext refreshScopedQueryContext(GitHub github, GHRepository repository, long installationId) {
        ScopedQueryContext qc = new ScopedQueryContext(this, repository, installationId).addExisting(github);
        Log.debugf("[%s] Refresh ScopedQueryContext for %s", installationId, repository.getFullName());
        scopedContexts.put("" + installationId, qc);
        scopedContexts.put(repository.getFullName(), qc);
        scopedContexts.put(ScopedQueryContext.toOrganizationName(repository.getFullName()), qc);
        return qc;
    }

    public ScopedQueryContext getDatastoreContext() {
        return getScopedQueryContext(getDataStore());
    }

    public ScopedQueryContext getScopedQueryContext(String scope) {
        ScopedQueryContext qc = scopedContexts.get(scope);
        if (qc == null) {
            qc = scopedContexts.get(ScopedQueryContext.toOrganizationName(scope));
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

        GHRepository repo = repoEvent.repository();
        String repoFullName = repo.getFullName();

        if (repoEvent.removed()) {
            scopedContexts.remove(repoFullName);

            scopedContexts.values().stream().filter(qc -> qc.getInstallationId() == repoEvent.installationId())
                    .findFirst().ifPresent(qc -> {
                        String name = ScopedQueryContext.toOrganizationName(qc.getRepository().getFullName());
                        scopedContexts.put("" + repoEvent.installationId(), qc);
                        scopedContexts.put(name, qc);
                    });
            return;
        } else {
            refreshScopedQueryContext(
                    repoEvent.github(),
                    repo,
                    repoEvent.installationId())
                    .addExisting(repoEvent.graphQLClient());
        }

        Optional<AdminConfigFile> repoConfig = repoEvent.getRepositoryConfig();
        UserManagementConfig userConfig = UserManagementConfig.getUserManagementConfig(repoConfig.orElse(null));
        if (userConfig != null && !userConfig.isDisabled()) {
            this.userConfig = userConfig;
        }
    }

    public GitHub getUserConnection(String nodeId, SecurityIdentity identity) {
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

    public ApplicationData getOpenApplication(MemberSession session, String applicationId) {
        if (applicationId == null) {
            return null;
        }
        ScopedQueryContext qc = getDatastoreContext();
        DataCommonItem issue = qc.getItem(EventType.issue, applicationId);

        ApplicationData application = new ApplicationData(session, issue);
        if (application.isOwner()) {
            Feedback feedback = getFeedback(qc, applicationId, issue.mostRecent());
            if (feedback != null) {
                application.setFeedback(feedback);
            }
        }
        return application;
    }

    public ApplicationData updateApplication(MemberSession session, CommonhausUser user,
            ApplicationPost applicationPost) throws Throwable {
        if (applicationPost == null) {
            return null;
        }

        MembershipApplication application = user.application();
        if (application != null) {
            ApplicationData existing = getOpenApplication(session, application.nodeId());
            if (existing != null && !existing.isOwner()) {
                application = null;
            }
        }

        ScopedQueryContext qc = getDatastoreContext();
        String content = ApplicationData.issueContent(session, applicationPost);
        Collection<DataLabel> labels = qc.findLabels(List.of("new-member"));
        if (labels.isEmpty()) {
            // TODO: config for labels / repo discovery
            DataLabel newLabel = qc.createLabel("new-member", "#78A658");
            if (newLabel != null) {
                labels = List.of(newLabel);
            }
        }

        DataCommonItem item = application == null
                ? qc.createItem(EventType.issue,
                        ApplicationData.createTitle(session),
                        content,
                        labels)
                : qc.updateItemDescription(EventType.issue, application.nodeId(), content);

        if (qc.hasErrors()) {
            throw qc.bundleExceptions();
        }

        return item == null
                ? null
                : new ApplicationData(session, item);
    }

    Feedback getFeedback(ScopedQueryContext qc, String nodeId, Date mostRecent) {
        List<DataCommonComment> comments = qc.getComments(nodeId,
                x -> ApplicationData.isUserFeedback(x.body) && ApplicationData.isNewer(x, mostRecent));

        return (comments == null || comments.isEmpty())
                ? null
                : new Feedback(comments.get(0));
    }
}
