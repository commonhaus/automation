package org.commonhaus.automation.hk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.config.EmailNotification;
import org.commonhaus.automation.config.RepoSource;
import org.commonhaus.automation.github.discovery.DiscoveryAction;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent.RdePriority;
import org.commonhaus.automation.github.queue.PeriodicUpdateQueue;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.github.watchers.FileWatcher;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdate;
import org.commonhaus.automation.github.watchers.FileWatcher.FileUpdateType;
import org.commonhaus.automation.hk.config.AdminBotConfig;
import org.commonhaus.automation.hk.config.HausKeeperConfig;
import org.commonhaus.automation.hk.config.UserManagementConfig;
import org.commonhaus.automation.hk.github.AppContextService;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.logging.Log;

@ApplicationScoped
public class UserManager {
    static final String ME = "userManager";

    @ApplicationScoped
    public static class ActiveHausKeeperConfig {
        protected final AtomicReference<Optional<HausKeeperConfig>> currentConfig = new AtomicReference<>(Optional.empty());
        protected final Set<String> attestationIds = new HashSet<>();

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

        protected void clear() {
            currentConfig.set(Optional.empty());
            attestationIds.clear();
        }

        protected void update(ScopedQueryContext qc, HausKeeperConfig config) {
            currentConfig.set(Optional.of(config));
            updateValidAttestations(qc, config.userManagement());
        }

        protected void updateValidAttestations(ScopedQueryContext homeQc, UserManagementConfig userConfig) {
            if (userConfig.isDisabled()) {
                return;
            }

            String attestationRepository = userConfig.attestations().repository();
            if (attestationRepository == null || attestationRepository.isBlank()) {
                Log.debugf("%s/updateValidAttestations: no attestations repository defined", ME);
                return;
            }
            ScopedQueryContext qc = homeQc.forPublicContent(attestationRepository);
            GHRepository repo = qc.getRepository(attestationRepository);
            GHContent content = qc.readSourceFile(repo, userConfig.attestations().filePath());
            if (content == null || qc.hasErrors()) {
                Log.debugf("%s/updateValidAttestations: filePath %s does not exist in %s", ME,
                        userConfig.attestations().filePath(), repo.getFullName());
                return;
            }
            JsonNode agreements = qc.readYamlContent(content);
            if (agreements == null || qc.hasErrors()) {
                qc.logAndSendContextErrors("[%s] updateValidAttestations: unable to parse %s from %s"
                        .formatted(ME, userConfig.attestations().filePath(), repo.getFullName()));
                return;
            }

            List<String> newIds = new ArrayList<>();
            JsonNode attestations = agreements.get("attestations");
            if (attestations != null && attestations.isObject()) {
                attestations.fields().forEachRemaining(entry -> newIds.add(entry.getKey()));
            }
            attestationIds.addAll(newIds);
            attestationIds.retainAll(newIds);
        }
    }

    @Inject
    protected AppContextService ctx;

    @Inject
    protected AdminBotConfig adminData;

    @Inject
    protected FileWatcher fileEvents;

    @Inject
    protected PeriodicUpdateQueue periodicSync;

    @Inject
    ActiveHausKeeperConfig hkConfig;

    /**
     * Event handler for repository discovery.
     * Specifically look for (and monitor) HausKeeper configuration.
     */
    protected void repositoryDiscovered(
            @Observes @Priority(value = RdePriority.APP_DISCOVERY) RepositoryDiscoveryEvent repoEvent) {
        DiscoveryAction action = repoEvent.action();
        long installationId = repoEvent.installationId();
        GHRepository repo = repoEvent.repository();
        String repoFullName = repo.getFullName();

        if (action.repository()
                && repoFullName.equals(adminData.datastore())) {
            // Read config from the datastore repository. Immediately
            Log.debugf("[%s] repoDiscovered: %s main=%s", ME, action.name(), repoFullName);
            if (action.added()) {
                // main repository for configuration
                ScopedQueryContext qc = new ScopedQueryContext(ctx, installationId, repo)
                        .withExisting(repoEvent.github());

                processConfigUpdate(qc);

                fileEvents.watchFile(ME,
                        installationId, repoFullName, HausKeeperConfig.PATH,
                        (fileUpdate) -> processFileUpdate(fileUpdate));
            }
        }
    }

    /**
     * Read organization configuration from repository.
     * Called by for file update events.
     */
    protected void processFileUpdate(FileUpdate fileUpdate) {
        GitHub github = fileUpdate.github();
        GHRepository repo = fileUpdate.repository();

        if (fileUpdate.updateType() == FileUpdateType.REMOVED) {
            Log.debugf("%s/processFileUpdate: %s deleted", repo.getFullName());
            // Leave the watcher, in case the file is re-added later
            // currentConfig.set(Optional.empty());
            if (repo.getFullName().equals(ctx.getDataStore())) {
                hkConfig.clear();
            }
            return;
        }

        ScopedQueryContext qc = new ScopedQueryContext(ctx, fileUpdate.installationId(), fileUpdate.repository())
                .withExisting(github);

        processConfigUpdate(qc);
    }

    protected void processConfigUpdate(ScopedQueryContext qc) {
        GHRepository repo = qc.getRepository();
        if (repo == null) {
            Log.warnf("%s/readOrgConfig: repository not set in QueryContext", ME);
            return;
        }
        GHContent content = qc.readSourceFile(repo, HausKeeperConfig.PATH);
        if (content == null || qc.hasErrors()) {
            Log.debugf("%s/processConfigUpdate: no %s in %s", ME, HausKeeperConfig.PATH, repo.getFullName());
            return;
        }
        HausKeeperConfig cfg = qc.readYamlContent(content, HausKeeperConfig.class);
        if (cfg == null || qc.hasErrors()) {
            qc.logAndSendContextErrors("%s/processConfigUpdate: unable to parse %s in %s"
                    .formatted(ME, HausKeeperConfig.PATH, repo.getFullName()));
            return;
        }
        Log.debugf("%s/processConfigUpdate: found %s in %s", ME, HausKeeperConfig.PATH, repo.getFullName());
        hkConfig.update(qc, cfg);
    }
}
