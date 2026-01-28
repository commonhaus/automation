package org.commonhaus.automation.hm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.commonhaus.automation.ContextService;
import org.commonhaus.automation.config.BotConfig;
import org.commonhaus.automation.github.discovery.BootstrapDiscoveryEvent;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent.RdePriority;
import org.commonhaus.automation.hm.OrganizationManager.OrganizationConfigState;
import org.commonhaus.automation.hm.ProjectManager.ProjectConfigState;
import org.commonhaus.automation.hm.github.AppContextService;
import org.commonhaus.automation.queue.PeriodicUpdateQueue;

import com.fasterxml.jackson.core.type.TypeReference;

import io.quarkus.logging.Log;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

@Singleton
public class TeamConflictResolver {
    static final TypeReference<Map<String, TeamOwnership>> TO_STATE_TYPE = new TypeReference<>() {
    };

    public enum OwnershipType {
        ORGANIZATION,
        PROJECT,
        PROJECT_CONFLICT,
        EMPTY
    }

    @Inject
    AppContextService ctx;

    @Inject
    PeriodicUpdateQueue updateQueue;

    @Inject
    BotConfig botConfig;

    private final Map<String, TeamOwnership> teamOwnership = new ConcurrentHashMap<>();

    void init(@Observes StartupEvent event) {
        if (LaunchMode.current() == LaunchMode.TEST) {
            return;
        }
        Path stateFilePath = getStateFile();
        if (stateFilePath == null) {
            return;
        }
        if (Files.exists(stateFilePath)) {
            try {
                String yaml = Files.readString(stateFilePath);
                if (yaml.isBlank()) {
                    Log.debugf("TeamConflictResolver: State file %s is empty", stateFilePath);
                } else {
                    Map<String, TeamOwnership> restoredState = ctx.parseYamlContent(yaml, TO_STATE_TYPE);
                    teamOwnership.putAll(restoredState);
                }
            } catch (IOException e) {
                ctx.logAndSendEmail("TeamConflictResolver",
                        "Failed to read state file %s: %s".formatted(stateFilePath, e), e);
            }
        }
    }

    void saveState(@Observes ShutdownEvent event) throws IOException {
        if (LaunchMode.current() == LaunchMode.TEST) {
            return;
        }
        Path stateFilePath = getStateFile();
        if (stateFilePath == null) {
            return;
        }

        Map<String, TeamOwnership> persistableState = new HashMap<>();

        for (var entry : teamOwnership.entrySet()) {
            TeamOwnership original = entry.getValue();

            // Replace org with EMPTY if non-null, preserve null
            OrganizationConfigState persistableOrg = original.org != null
                    ? OrganizationManager.EMPTY
                    : null;

            // Replace all projects with EMPTY, preserving taskGroup keys
            Map<String, ProjectConfigState> persistableProjects = new HashMap<>();
            for (String taskGroup : original.projects.keySet()) {
                persistableProjects.put(taskGroup, ProjectManager.EMPTY);
            }

            persistableState.put(entry.getKey(),
                    new TeamOwnership(persistableOrg, persistableProjects));
        }

        Log.debugf("TeamConflictResolver / persist %s", persistableState);
        try {
            // Write the current state of the journal (including if empty)
            String yaml = ContextService.yamlMapper.writeValueAsString(persistableState);
            Files.writeString(stateFilePath, yaml);
        } catch (IOException e) {
            ctx.logAndSendEmail("TeamConflictResolver", "Failed to save state on shutdown: %s".formatted(e), e);
        }
    }

    protected void cleanupStaleEntries(
            @Observes @Priority(value = RdePriority.APP_DISCOVERY) BootstrapDiscoveryEvent bootstrapComplete) {
        if (LaunchMode.current() == LaunchMode.TEST) {
            return;
        }
        for (var entry : teamOwnership.entrySet()) {
            TeamOwnership original = entry.getValue();

            var cleanedOrg = original.org == OrganizationManager.EMPTY
                    ? null
                    : original.org;

            Map<String, ProjectConfigState> cleanedProjects = new HashMap<>(original.projects);
            cleanedProjects.values().removeIf(v -> v == ProjectManager.EMPTY);

            // Create new TeamOwnership with cleaned values
            entry.setValue(new TeamOwnership(cleanedOrg, cleanedProjects));
        }
        teamOwnership.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    Path getStateFile() {
        String directory = botConfig.queue().stateDirectory().orElse(null);
        if (directory == null) {
            Log.debug("TeamConflictResolver: No state directory configured");
            return null;
        }
        Path stateDir = Path.of(directory);
        try {
            Files.createDirectories(stateDir);
        } catch (IOException e) {
            ctx.logAndSendEmail("TeamConflictResolver",
                    "State directory can not be created: %s".formatted(stateDir), e);
            return null;
        }
        Path stateFilePath = stateDir.resolve("hm-team-ownership.yaml");
        return stateFilePath;
    }

    /**
     * Register teams managed by organization config.
     * Organization management has priority.
     */
    public void registerOrgTeams(OrganizationConfigState orgState) {
        Map<String, Runnable> updates = new HashMap<>();

        // Register org-managed teams.
        for (var teamName : orgState.teams()) {
            var oldTo = teamOwnership.get(teamName); // Get before compute
            var newTo = teamOwnership.compute(teamName, (k, v) -> {
                return new TeamOwnership(orgState, v == null ? Map.of() : v.projects);
            });

            // Only react to actual changes
            if (oldTo != null && !Objects.equals(oldTo, newTo)) {
                if (newTo.hasConflict()) {
                    // Only notify for NEW org conflicts (projects that weren't already blocked)
                    if (oldTo == null || oldTo.org == null) {
                        handleConflict(teamName, newTo);
                    }
                }
                updateProjects(updates, newTo, null);
            }
        }

        // queue updates for each task group
        updates.forEach((k, v) -> updateQueue.queueReconciliation(k, v));
    }

    public void releaseOrgTeams(Set<String> removedTeams) {
        Map<String, Runnable> updates = new HashMap<>();
        // Remove teams that are no longer managed by the organization
        for (String teamName : removedTeams) {
            var to = teamOwnership.compute(teamName, (k, v) -> {
                if (v == null) {
                    return null; // Team not tracked
                }
                TeamOwnership newOwner = new TeamOwnership(null, v.projects);
                return newOwner.isEmpty()
                        ? null
                        : newOwner;
            });
            updateProjects(updates, to, null);
        }
        // queue updates for each task group
        updates.forEach((k, v) -> updateQueue.queueReconciliation(k, v));
    }

    /**
     * Register teams for a project and return valid (non-conflicted) teams.
     * Filter/remove conflicting teams.
     */
    public void registerProjectTeams(ProjectConfigState projectConfig) {
        Map<String, Runnable> updates = new HashMap<>();

        // Validate against org teams and other projects
        for (var teamName : projectConfig.targetTeams()) {
            var oldTo = teamOwnership.get(teamName); // Get before compute

            var newTo = teamOwnership.compute(teamName, (k, v) -> {
                if (v == null) {
                    return new TeamOwnership(null, Map.of(projectConfig.taskGroup(), projectConfig)); // ← Creates type
                                                                                                      // = PROJECT
                }

                var projects = new HashMap<>(v.projects);
                projects.put(projectConfig.taskGroup(), projectConfig); // ← Always adds, even for conflicts
                return new TeamOwnership(v.org, projects);
            });

            if (newTo.hasConflict()) {
                projectConfig.addBlockedTeam(teamName);
            }

            // Only handle conflicts and updates if something actually changed
            if (oldTo != null && !Objects.equals(oldTo, newTo)) {
                if (newTo.hasConflict()) {
                    // Only send notifications for NEW conflicts
                    handleConflict(teamName, newTo);
                }
                updateProjects(updates, newTo, projectConfig.taskGroup());
            }
        }

        // queue updates for each task group
        updates.forEach((k, v) -> updateQueue.queueReconciliation(k, v));
    }

    /**
     * Release teams for a project when no longer managed by team configuration
     *
     * @param projectState
     * @param removedTeams
     */
    public void releaseProjectTeams(ProjectConfigState projectState, Set<String> removedTeams) {
        Map<String, Runnable> updates = new HashMap<>();
        // Remove teams that are no longer managed by the organization
        for (String teamName : removedTeams) {
            var to = teamOwnership.compute(teamName, (k, v) -> {
                if (v == null) {
                    return null; // Team not tracked
                }

                // Remove the project
                var projects = new HashMap<>(v.projects);
                projects.remove(projectState.taskGroup());
                TeamOwnership newOwner = new TeamOwnership(v.org, projects);

                return newOwner.isEmpty()
                        ? null
                        : newOwner;
            });
            updateProjects(updates, to, null);
        }

        // queue updates for each task group
        updates.forEach((k, v) -> updateQueue.queueReconciliation(k, v));
    }

    void updateProjects(Map<String, Runnable> updates, TeamOwnership to, String excludeTaskGroup) {
        if (to == null)
            return;

        switch (to.type) {
            case ORGANIZATION, PROJECT_CONFLICT -> {
                // Org took over - ALL projects need to refresh to stop managing
                // OR Conflict state - no one should manage, but projects might need to stop
                for (var project : to.projects.values()) {
                    if (!project.taskGroup().equals(excludeTaskGroup)) {
                        updates.put(project.taskGroup(), project.refresh());
                    }
                }
            }
            case PROJECT -> {
                // Single project can now manage - trigger its refresh
                if (!to.projects.isEmpty()) {
                    var project = to.projects.values().iterator().next();
                    updates.put(project.taskGroup(), project.refresh());
                }
            }
            case EMPTY -> {
                // No one managing - no updates needed
            }
        }
    }

    /**
     * Send conflict notification emails based on ownership type and conflicting
     * parties
     */
    private void handleConflict(String teamName, TeamOwnership ownership) {
        switch (ownership.type) {
            case ORGANIZATION -> {
                // Org has precedence - notify the project trying to claim the team
                String message = """
                        Team management conflict:

                        %s is managed by organization configuration and cannot be managed at project level.

                        Please remove this team from your project configuration.
                        """.formatted(teamName);

                for (var project : ownership.projects.values()) {
                    var addresses = ctx.getErrorAddresses(project.emailNotifications());
                    ctx.logAndSendEmail("TeamConflictResolver", message, null, addresses);
                }
            }

            case PROJECT_CONFLICT -> {
                // Project-level conflict - notify all involved projects
                String conflictingProjects = ownership.projects.values().stream()
                        .map(p -> p.repoFullName())
                        .collect(Collectors.joining(", "));

                String message = """
                        Team management conflict:

                        %s is configured in multiple projects: %s.

                        Please remove this team from your project configuration.

                        This team will not be managed until the conflict is resolved.
                        """.formatted(teamName, conflictingProjects);

                for (var project : ownership.projects.values()) {
                    var addresses = ctx.getErrorAddresses(project.emailNotifications());
                    ctx.logAndSendEmail("TeamConflictResolver", message, null, addresses);
                }

                Log.warnf("[TeamConflictResolver] Project conflict for team %s: %s",
                        teamName, conflictingProjects);
            }

            case PROJECT, EMPTY -> {
                // This shouldn't happen during conflict detection, but log it
                Log.warnf("[TeamConflictResolver] Unexpected ownership type (%s) for team %s", ownership.type,
                        teamName);
            }
        }
    }

    /**
     * Hard-reset of the file watcher.
     * This is useful for testing.
     */
    public void reset() {
        teamOwnership.clear();
    }

    static class TeamOwnership {
        final OwnershipType type;
        final OrganizationConfigState org;
        final Map<String, ProjectConfigState> projects;

        public TeamOwnership(OrganizationConfigState org, Map<String, ProjectConfigState> projects) {
            this.org = org;
            this.projects = projects == null ? Map.of() : projects;

            if (this.org != null) {
                this.type = OwnershipType.ORGANIZATION;
            } else if (this.projects.isEmpty()) {
                this.type = OwnershipType.EMPTY;
            } else if (this.projects.size() == 1) {
                this.type = OwnershipType.PROJECT;
            } else {
                this.type = OwnershipType.PROJECT_CONFLICT;
            }
        }

        boolean isEmpty() {
            return org == null && projects.isEmpty();
        }

        boolean hasConflict() {
            return (org != null && !projects.isEmpty())
                    || projects.size() > 1;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            TeamOwnership that = (TeamOwnership) o;
            return type == that.type &&
                    Objects.equals(org, that.org) &&
                    Objects.equals(projects, that.projects);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, org, projects);
        }

        @Override
        public String toString() {
            return "TeamOwnership [type=" + type + ", hasConflict()=" + hasConflict() + "]";
        }
    }
}
