package org.commonhaus.automation.hk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.commonhaus.automation.config.EmailNotification;
import org.commonhaus.automation.config.RepoSource;
import org.commonhaus.automation.github.scopes.ScopedQueryContext;
import org.commonhaus.automation.hk.config.AliasManagementConfig;
import org.commonhaus.automation.hk.config.HausKeeperConfig;
import org.commonhaus.automation.hk.config.UserManagementConfig;
import org.commonhaus.automation.queue.PeriodicUpdateQueue;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHRepository;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.logging.Log;

@ApplicationScoped
public class ActiveHausKeeperConfig {
    protected final AtomicReference<Optional<HausKeeperConfig>> currentConfig = new AtomicReference<>(Optional.empty());
    protected final Set<String> attestationIds = new HashSet<>();
    protected final Map<String, Runnable> callbacks = new ConcurrentHashMap<>();

    @Inject
    PeriodicUpdateQueue updateQueue;

    public void notifyOnUpdate(String id, Runnable callback) {
        if (callback == null) {
            return;
        }
        callbacks.put(id, callback);
    }

    public boolean isReady() {
        return currentConfig.get().isPresent();
    }

    public AliasManagementConfig getProjectAliasesConfig() {
        return currentConfig.get().map(HausKeeperConfig::projectAliases)
                .orElse(AliasManagementConfig.DISABLED);
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

        // Queue callbacks for config consumers
        for (var callback : callbacks.entrySet()) {
            updateQueue.queueReconciliation(callback.getKey(), callback.getValue());
        }
    }

    protected void updateValidAttestations(ScopedQueryContext homeQc, UserManagementConfig userConfig) {
        if (userConfig.isDisabled()) {
            return;
        }

        String attestationRepository = userConfig.attestations().repository();
        if (attestationRepository == null || attestationRepository.isBlank()) {
            Log.debugf("%s/updateValidAttestations: no attestations repository defined", UserManager.ME);
            return;
        }
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
}
