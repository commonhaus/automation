package org.commonhaus.automation.github.watchers;

import static org.commonhaus.automation.github.context.GitHubQueryContext.toOrganizationName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.commonhaus.automation.ContextService;
import org.commonhaus.automation.config.BotConfig;

import com.fasterxml.jackson.core.type.TypeReference;

import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkus.logging.Log;

@Singleton
public class GitHubEventFilter {

    private static final String FILENAME = "ignored-installations.yaml";
    private static final TypeReference<Map<Long, String>> TYPE_REF = new TypeReference<>() {
    };

    @Inject
    BotConfig botConfig;

    private Set<Long> blocklist = Set.of();
    private Set<String> blocklistByName = Set.of();

    private Set<Long> allowlist = ConcurrentHashMap.newKeySet();
    private Set<String> allowlistByName = Set.of();

    @PostConstruct
    void init() {
        // Configured allow list, if present
        this.allowlistByName = botConfig.allowedInstallations().orElse(Set.of())
                .stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::toLowerCase)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        // read shared blocklist
        String directory = botConfig.queue().stateDirectory().orElse(null);
        if (directory == null) {
            return;
        }
        Path blockFile = Path.of(directory, FILENAME);
        if (!Files.exists(blockFile)) {
            return;
        }
        try {
            String content = Files.readString(blockFile);
            Map<Long, String> parsed = ContextService.yamlMapper.readValue(content, TYPE_REF);
            if (parsed != null && !parsed.isEmpty()) {
                blocklist = Set.copyOf(parsed.keySet());
                blocklistByName = parsed.values().stream()
                        .filter(v -> v != null && !v.isBlank())
                        .map(String::toLowerCase)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                Log.infof("[GitHubEventFilter] Loaded %d blocked installation(s) from %s", blocklist.size(), blockFile);
            }
        } catch (IOException e) {
            Log.warnf("[GitHubEventFilter] Could not read %s; no installations will be blocked: %s", blockFile, e.getMessage());
            blocklist = Set.of();
            blocklistByName = Set.of();
        }
    }

    public void addInstallation(Long installationId, String login) {
        if (installationId == null || login == null || login.isBlank()) {
            return;
        }
        String normalizedLogin = login.toLowerCase();
        if (blocklistByName.contains(normalizedLogin)) {
            return;
        }
        if (allowlistByName.contains(normalizedLogin)) {
            allowlist.add(installationId);
        }
    }

    /**
     * Returns {@code true} if the installation associated with the given event
     * is on the blocklist.
     *
     * @param event the GitHub webhook event
     * @return {@code true} if blocked, {@code false} if the installation ID is
     *         {@code null} or not on the blocklist
     */
    public boolean isBlocked(GitHubEvent event) {
        Long installationId = event.getInstallationId();
        if (event.getRepository().isPresent()) {
            return isBlocked(installationId, toOrganizationName(event.getRepository().get()));
        }
        if (installationId == null) {
            return false;
        }
        return isBlocked(installationId);
    }

    public boolean isBlocked(long ghiId, String orgName) {
        if (isExplicitlyBlocked(ghiId)) {
            return true;
        }
        if (isBlockedLogin(orgName)) {
            return true;
        }
        addInstallation(ghiId, orgName);
        return isBlocked(ghiId);
    }

    /**
     * Returns {@code true} if the given installation ID is on the blocklist.
     *
     * @param installationId the installation ID to check
     * @return {@code true} if blocked
     */
    public boolean isBlocked(long installationId) {
        if (isExplicitlyBlocked(installationId)) {
            return true;
        }
        if (!allowlistByName.isEmpty()) {
            return !allowlist.contains(installationId);
        }
        return false;
    }

    /**
     * Returns {@code true} if the given login/org name is on the blocklist.
     * Comparison is case-insensitive.
     *
     * @param login the GitHub login or org name to check
     * @return {@code true} if blocked
     */
    public boolean isBlockedLogin(String login) {
        if (login == null || login.isBlank()) {
            return !allowlistByName.isEmpty();
        }
        String normalizedLogin = login.toLowerCase();
        if (blocklistByName.contains(normalizedLogin)) {
            return true;
        }
        if (!allowlistByName.isEmpty()) {
            return !allowlistByName.contains(normalizedLogin);
        }
        return false;
    }

    public boolean isExplicitlyBlocked(long installationId) {
        return blocklist.contains(installationId);
    }

    /**
     * For testing only: directly replace the blocklist.
     */
    void setBlocklistForTesting(Set<Long> ids) {
        blocklist = Set.copyOf(ids);
    }

    /**
     * For testing only: directly replace the name blocklist.
     */
    void setNameBlocklistForTesting(Set<String> names) {
        blocklistByName = names.stream()
                .map(String::toLowerCase)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
