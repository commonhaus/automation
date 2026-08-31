package org.commonhaus.automation.hk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.config.EmailNotification;
import org.commonhaus.automation.config.RepoSource;
import org.commonhaus.automation.github.context.GitHubQueryContext;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent.RdePriority;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.github.watchers.FileWatcher;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdate;
import org.commonhaus.automation.hk.config.HausKeeperConfig;
import org.commonhaus.automation.hk.config.OrganizationDomains;
import org.commonhaus.automation.hk.config.UserManagementConfig;
import org.commonhaus.automation.hk.github.AppContextService;
import org.commonhaus.automation.queue.PeriodicUpdateQueue;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHRepository;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.logging.Log;

@ApplicationScoped
public class ActiveHausKeeperConfig {
    static final String ORG_CONFIG_TASK_GROUP = "haus-keeper-org-config";

    protected final AtomicReference<Optional<HausKeeperConfig>> currentConfig = new AtomicReference<>(Optional.empty());
    protected final Set<String> attestationIds = new HashSet<>();
    protected final Map<String, Runnable> callbacks = new ConcurrentHashMap<>();
    protected final Map<String, Consumer<Set<String>>> domainChangeCallbacks = new ConcurrentHashMap<>();

    protected final AtomicReference<Map<String, Set<String>>> organizationDomains = new AtomicReference<>(Map.of());
    protected final AtomicReference<RepoSource> watchedOrgConfig = new AtomicReference<>(null);
    protected final AtomicReference<RepoSource> pendingOrgConfig = new AtomicReference<>(null);

    @Inject
    PeriodicUpdateQueue updateQueue;

    @Inject
    FileWatcher fileWatcher;

    @Inject
    AppContextService ctx;

    public void notifyOnUpdate(String id, Runnable callback) {
        if (callback == null) {
            return;
        }
        callbacks.put(id, callback);
    }

    /**
     * Register a callback to be notified when the regrouped organization
     * domain map changes. The callback receives the set of project names
     * whose authoritative domains actually changed, in a single batched call.
     */
    public void notifyOnDomainChange(String id, Consumer<Set<String>> callback) {
        if (callback == null) {
            return;
        }
        domainChangeCallbacks.put(id, callback);
    }

    public boolean isReady() {
        return currentConfig.get().isPresent();
    }

    public UserManagementConfig getConfig() {
        return currentConfig.get().map(HausKeeperConfig::userManagement).orElse(UserManagementConfig.DISABLED);
    }

    public EmailNotification getAddresses() {
        return currentConfig.get().map(HausKeeperConfig::emailNotifications).orElse(EmailNotification.UNDEFINED);
    }

    public RepoSource getAttestationConfig() {
        UserManagementConfig userConfig = getConfig();
        if (userConfig.isDisabled()) {
            return null;
        }
        return userConfig.attestations();
    }

    public boolean isValidAttestation(String id) {
        // If none are defined/found, anything goes
        return attestationIds.isEmpty() || attestationIds.contains(id);
    }

    /**
     * @return the authoritative domain set for the given project name, or an
     *         empty set if the project has no known authoritative domains
     *         (either because it has no entry, or the source hasn't been read yet)
     */
    public Set<String> getDomainsForProject(String projectName) {
        return organizationDomains.get().getOrDefault(projectName, Set.of());
    }

    protected void clear() {
        currentConfig.set(Optional.empty());
        attestationIds.clear();
    }

    protected void update(ScopedQueryContext qc, HausKeeperConfig config) {
        currentConfig.set(Optional.of(config));
        updateValidAttestations(qc, config.userManagement());
        updateOrganizationDomains(qc, config.userManagement(), config.organizationConfig());

        // Queue callbacks for config consumers
        for (var callback : callbacks.entrySet()) {
            updateQueue.queueReconciliation(callback.getKey(), callback.getValue());
        }
    }

    /**
     * Retry a deferred organizationConfig once its target org's installation
     * is discovered. Installations are discovered asynchronously at startup,
     * so organizationConfig (often a different org than the home repo) may
     * not have a known installation yet when first read; pendingOrgConfig
     * records that a retry is owed.
     */
    protected void onRepositoryDiscovered(
            @Observes @Priority(value = RdePriority.APP_DISCOVERY) RepositoryDiscoveryEvent repoEvent) {
        if (!repoEvent.added()) {
            return;
        }
        RepoSource orgConfig = pendingOrgConfig.get();
        if (orgConfig == null) {
            return;
        }
        String targetOrg = GitHubQueryContext.toOrganizationName(orgConfig.repository());
        String discoveredOrg = GitHubQueryContext.toOrganizationName(repoEvent.repository().getFullName());
        if (!targetOrg.equals(discoveredOrg)) {
            return;
        }
        ScopedQueryContext qc = ctx.getOrgScopedQueryContext(targetOrg);
        if (qc == null) {
            return;
        }
        Log.debugf("%s/onRepositoryDiscovered: retrying deferred organizationConfig %s now that %s is known",
                UserManager.ME, orgConfig, targetOrg);
        pendingOrgConfig.set(null);
        rewatchOrganizationConfig(orgConfig, qc);
        Map<String, Set<String>> regrouped = readAndRegroupOrganizationDomains(qc, orgConfig);
        if (regrouped != null) {
            organizationDomains.set(regrouped);
        }
    }

    protected void updateValidAttestations(ScopedQueryContext homeQc, UserManagementConfig userConfig) {
        if (userConfig.isDisabled()) {
            return;
        }

        if (userConfig.attestations().isEmpty()) {
            Log.debugf("%s/updateValidAttestations: no attestations defined in %s", UserManager.ME, userConfig);
            return;
        } else {
            Log.debugf("%s/updateValidAttestations: validating attestations in %s", UserManager.ME,
                    userConfig.attestations());
        }
        String attestationRepository = userConfig.attestations().repository();
        ScopedQueryContext qc = homeQc.forPublicContent(attestationRepository);
        GHRepository repo = qc.getRepository(attestationRepository);
        if (repo == null || qc.hasErrors()) {
            qc.logAndSendContextErrors("[%s] updateValidAttestations: unable to access repository %s"
                    .formatted(UserManager.ME, attestationRepository));
            return;
        }
        GHContent content = qc.readSourceFile(repo, userConfig.attestations().filePath());
        if (content == null || qc.hasErrors()) {
            Log.debugf("%s/updateValidAttestations: filePath %s does not exist in %s", UserManager.ME,
                    userConfig.attestations().filePath(), repo.getFullName());
            return;
        }
        JsonNode agreements = qc.readYamlContent(content);
        if (agreements == null || qc.hasErrors()) {
            qc.logAndSendContextErrors("[%s] updateValidAttestations: unable to parse %s from %s"
                    .formatted(UserManager.ME, userConfig.attestations().filePath(), repo.getFullName()));
            return;
        }

        List<String> newIds = new ArrayList<>();
        JsonNode attestations = agreements.get("attestations");
        if (attestations != null && attestations.isObject()) {
            for (var e : attestations.properties()) {
                newIds.add(e.getKey());
            }
        }
        attestationIds.addAll(newIds);
        attestationIds.retainAll(newIds);
    }

    protected void updateOrganizationDomains(ScopedQueryContext homeQc, UserManagementConfig userConfig,
            RepoSource configuredOrgConfig) {
        // A disabled config has no organizationConfig to read, but any previously
        // registered watch (from when the config was enabled) must still be torn
        // down -- otherwise it keeps firing indefinitely for a disabled feature.
        RepoSource orgConfig = userConfig.isDisabled() ? null : configuredOrgConfig;

        if (userConfig.isDisabled()) {
            pendingOrgConfig.set(null);
            rewatchOrganizationConfig(null, homeQc);
            return;
        }

        if (orgConfig == null || orgConfig.isEmpty()) {
            Log.debugf("%s/updateOrganizationDomains: no organizationConfig defined", UserManager.ME);
            pendingOrgConfig.set(null);
            rewatchOrganizationConfig(null, homeQc);
            return;
        }

        ScopedQueryContext qc = resolveOrgConfigContext(orgConfig, homeQc);
        if (qc == null) {
            // Target org's installation isn't known yet (e.g. discovered
            // asynchronously at startup, after this org's config was read).
            // Defer: leave the existing watch/cache untouched and retry via
            // onRepositoryDiscovered once that installation shows up.
            Log.debugf("%s/updateOrganizationDomains: no installation yet for %s; deferring", UserManager.ME, orgConfig);
            pendingOrgConfig.set(orgConfig);
            return;
        }

        pendingOrgConfig.set(null);
        rewatchOrganizationConfig(orgConfig, qc);
        Map<String, Set<String>> regrouped = readAndRegroupOrganizationDomains(qc, orgConfig);
        if (regrouped != null) {
            organizationDomains.set(regrouped);
        }
    }

    /**
     * Read and regroup cf-haus-organization.yml. Returns null (and alerts) on
     * a read/parse failure so the caller can leave the existing cache untouched;
     * this is an availability failure of the authoritative source, not a reason
     * to wipe out last-known-good data.
     */
    private Map<String, Set<String>> readAndRegroupOrganizationDomains(ScopedQueryContext qc, RepoSource orgConfig) {
        GHRepository repo = qc.getRepository(orgConfig.repository());
        if (repo == null || qc.hasErrors()) {
            qc.logAndSendContextErrors("[%s] updateOrganizationDomains: unable to access repository %s"
                    .formatted(UserManager.ME, orgConfig.repository()));
            return null;
        }
        GHContent content = qc.readSourceFile(repo, orgConfig.filePath());
        if (content == null || qc.hasErrors()) {
            qc.logAndSendContextErrors("[%s] updateOrganizationDomains: unable to read %s from %s"
                    .formatted(UserManager.ME, orgConfig.filePath(), repo.getFullName()));
            return null;
        }
        OrganizationDomains parsed = qc.readYamlContent(content, OrganizationDomains.class);
        if (parsed == null || qc.hasErrors()) {
            qc.logAndSendContextErrors("[%s] updateOrganizationDomains: unable to parse %s from %s"
                    .formatted(UserManager.ME, orgConfig.filePath(), repo.getFullName()));
            return null;
        }
        return parsed.regroup(ProjectAliasManager::toProjectName);
    }

    /**
     * Register/re-register the FileWatcher when the organizationConfig RepoSource
     * changes (including to/from empty), so stale watches don't accumulate
     * across successive HausKeeperConfig reads. The watch must be registered
     * under the installation that owns the target repo -- often a different
     * org than the home repo -- or GitHub won't deliver push events for it;
     * callers only pass a non-null orgConfig once qc is resolved to that
     * installation (see resolveOrgConfigContext).
     */
    private void rewatchOrganizationConfig(RepoSource orgConfig, ScopedQueryContext qc) {
        RepoSource previous = watchedOrgConfig.get();
        boolean previousPresent = previous != null && !previous.isEmpty();
        boolean newPresent = orgConfig != null && !orgConfig.isEmpty();

        if (previousPresent && previous.equals(orgConfig)) {
            return; // unchanged; nothing to do
        }

        if (previousPresent) {
            fileWatcher.unwatchFile(ORG_CONFIG_TASK_GROUP, previous.repository(), previous.filePath());
        }
        if (newPresent) {
            fileWatcher.watchFile(ORG_CONFIG_TASK_GROUP, qc.getInstallationId(),
                    orgConfig.repository(), orgConfig.filePath(),
                    (fileUpdate) -> onOrganizationConfigChanged(fileUpdate, orgConfig));
        }
        watchedOrgConfig.set(newPresent ? orgConfig : null);
    }

    /**
     * Resolve the ScopedQueryContext for the installation that owns orgConfig's
     * repository. Returns null (rather than falling back to homeQc's installation)
     * if that org's installation isn't known yet -- homeQc has no access to a
     * different org's repository and querying it anyway produces a misleading 404.
     */
    private ScopedQueryContext resolveOrgConfigContext(RepoSource orgConfig, ScopedQueryContext homeQc) {
        return homeQc.forOrganization(orgConfig.repository(), homeQc.isDryRun());
    }

    /**
     * FileWatcher callback: re-read, re-regroup, diff against the cached map,
     * and if anything changed, update the cache and notify callbacks with the
     * set of changed project names (one batched call, not one per project).
     * Builds its ScopedQueryContext fresh from the FileUpdate rather than
     * reusing the one captured at watch-registration time, since this
     * callback fires repeatedly for as long as orgConfig is unchanged.
     */
    private void onOrganizationConfigChanged(FileUpdate fileUpdate, RepoSource orgConfig) {
        ScopedQueryContext qc = new ScopedQueryContext(ctx, fileUpdate.installationId(), fileUpdate.repository())
                .withExisting(fileUpdate.github());
        Map<String, Set<String>> newRegrouped = readAndRegroupOrganizationDomains(qc, orgConfig);
        if (newRegrouped == null) {
            return; // read/parse failed; alert already sent, cache left untouched
        }

        Map<String, Set<String>> previous = organizationDomains.get();
        Set<String> changedProjects = diffProjectDomains(previous, newRegrouped);
        if (changedProjects.isEmpty()) {
            return;
        }

        organizationDomains.set(newRegrouped);
        for (var callback : domainChangeCallbacks.entrySet()) {
            updateQueue.queueReconciliation(callback.getKey(), () -> callback.getValue().accept(changedProjects));
        }
    }

    private static Set<String> diffProjectDomains(Map<String, Set<String>> oldMap, Map<String, Set<String>> newMap) {
        Set<String> changed = new HashSet<>();
        for (var entry : newMap.entrySet()) {
            if (!entry.getValue().equals(oldMap.get(entry.getKey()))) {
                changed.add(entry.getKey());
            }
        }
        for (String projectName : oldMap.keySet()) {
            if (!newMap.containsKey(projectName)) {
                changed.add(projectName);
            }
        }
        return changed;
    }
}
