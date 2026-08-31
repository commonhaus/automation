package org.commonhaus.automation.github.watchers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

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

    @PostConstruct
    void init() {
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
                Log.infof("[GitHubEventFilter] Loaded %d blocked installation(s) from %s", blocklist.size(), blockFile);
            }
        } catch (IOException e) {
            Log.warnf("[GitHubEventFilter] Could not read %s; no installations will be blocked: %s", blockFile, e.getMessage());
            blocklist = Set.of();
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
        if (event.getInstallationId() == null) {
            return false;
        }
        return isBlocked(event.getInstallationId());
    }

    /**
     * Returns {@code true} if the given installation ID is on the blocklist.
     *
     * @param installationId the installation ID to check
     * @return {@code true} if blocked
     */
    public boolean isBlocked(long installationId) {
        return blocklist.contains(installationId);
    }

    /**
     * For testing only: directly replace the blocklist.
     */
    void setBlocklistForTesting(Set<Long> ids) {
        blocklist = Set.copyOf(ids);
    }
}
