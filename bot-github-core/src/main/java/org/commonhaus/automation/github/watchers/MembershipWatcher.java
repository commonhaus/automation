package org.commonhaus.automation.github.watchers;

import static org.commonhaus.automation.github.context.GitHubTeamService.getFullTeamName;
import static org.commonhaus.automation.github.context.GitHubTeamService.refreshCollaborators;
import static org.commonhaus.automation.github.context.GitHubTeamService.refreshTeam;
import static org.commonhaus.automation.github.context.QueryContext.toOrganizationName;
import static org.commonhaus.automation.github.context.QueryContext.toRelativeName;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.commonhaus.automation.github.context.ActionType;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.github.context.GitHubTeamService;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent;
import org.commonhaus.automation.github.discovery.RepositoryDiscoveryEvent.RdePriority;
import org.commonhaus.automation.github.queue.PeriodicUpdateQueue;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTeam;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;

import io.quarkus.logging.Log;

@ApplicationScoped
public class MembershipWatcher {
    static final String ME = "membershipWatcher";

    final Map<String, WatchedTeams> orgWatchers = new HashMap<>();

    @Inject
    GitHubTeamService teamService;

    @Inject
    PeriodicUpdateQueue periodicSync;

    /**
     * Watch for repository discovery events and clean up watchers
     * if repositories or installations (association between GH App and an Organization)
     * are removed
     *
     * @param repoEvent
     */
    protected void onRepositoryDiscovery(
            @Observes @Priority(value = RdePriority.WATCHER_DISCOVERY) RepositoryDiscoveryEvent repoEvent) {
        if (repoEvent.removed()) {
            if (repoEvent.installation()) {
                // If an entire installation is removed, clean up all watchers for that installation
                long installationId = repoEvent.installationId();
                orgWatchers.entrySet().removeIf(entry -> entry.getValue().installationId == installationId);
                Log.debugf("%s: cleared watchers for installation %d", ME, installationId);
            } else {
                // Otherwise just remove watchers for the specific repository
                String repoFullName = repoEvent.repository().getFullName();
                String orgName = toOrganizationName(repoFullName);
                WatchedTeams watcher = orgWatchers.get(orgName);
                if (watcher != null) {
                    watcher.watchedResources.remove(repoFullName);
                }
                Log.debugf("%s: cleared watchers for repository %s", ME, repoFullName);
            }
        }
    }

    /**
     * @param taskGroupName Name of the task group to use for periodic updates
     * @param installationId Installation ID for the team
     * @param resourceFullName Full team name (org/team)
     * @param callback Callback function to invoke when the team is updated
     * @see #watchMembers(String, long, String, String, Consumer)
     */
    public void watchMembers(String taskGroupName, long installationId,
            MembershipUpdateType type, String resourceFullName, Consumer<MembershipUpdate> callback) {
        String orgName = toOrganizationName(resourceFullName);

        // avoid conflicts within the org:
        // use relative name for teams, full name for repositories
        String key = type == MembershipUpdateType.TEAM
                ? toRelativeName(orgName, resourceFullName)
                : resourceFullName;

        orgWatchers.computeIfAbsent(orgName, k -> new WatchedTeams(orgName, installationId))
                .add(key, new TaskCallback<MembershipUpdate>(taskGroupName, callback));
    }

    /**
     * Remove watcher for a team or repository
     *
     * @param type Membership update type
     * @param resourceFullName Full team name (org/team) or repository name
     */
    public void unwatch(MembershipUpdateType type, String resourceFullName) {
        String orgName = toOrganizationName(resourceFullName);

        // avoid conflicts within the org:
        // use relative name for teams, full name for repositories
        String key = type == MembershipUpdateType.TEAM
                ? toRelativeName(orgName, resourceFullName)
                : resourceFullName;

        WatchedTeams watcher = orgWatchers.get(orgName);
        if (watcher != null) {
            watcher.watchedResources.remove(key);
        }
    }

    public void unwatchAll(String taskGroup) {
        for (var entry : orgWatchers.entrySet()) {
            entry.getValue().watchedResources.values().removeIf(callbacks -> {
                callbacks.removeIf(callback -> callback.taskGroupName().equals(taskGroup));
                return callbacks.isEmpty();
            });
        }
        orgWatchers.values().removeIf(x -> x.watchedResources.isEmpty());
    }

    /**
     * An event fired due to activity relating to a team
     * <p>
     * This is a combination of team membership events
     * and team events (addition/removal of a team from an organization)
     *
     * @param event
     * @param teamEvent
     */
    public void handleTeamEvent(TeamEvent teamEvent) {
        String orgName = teamEvent.organization().getLogin();
        String teamFullName = getFullTeamName(
                teamEvent.organization(), teamEvent.team());

        Log.debugf("[%s-%s] team membership change in %s for %s", ME,
                teamEvent.installationId(), orgName, teamFullName);

        // Always clear cache for modified team
        refreshTeam(teamFullName);

        WatchedTeams watcher = orgWatchers.get(orgName);
        if (watcher == null) {
            return;
        }
        MembershipUpdate update = new MembershipUpdate(
                MembershipUpdateType.TEAM,
                orgName,
                teamEvent);

        // avoid conflicts: teams always use relative name (within org);
        watcher.handleUpdate(teamEvent.team().getName(), update, periodicSync);
    }

    /**
     * An event fired due to activity relating to a repository member (collaborator)
     *
     * - An collaborator was added to a team.
     * - An collaborator was removed from a team.
     *
     * Primary attributes of the webhook: organization, repository, member
     *
     * @param repositoryEvent
     */
    public void handleCollaboratorEvent(RepositoryEvent repositoryEvent) {
        GHRepository repo = repositoryEvent.repository();
        String repoFullName = repo.getFullName();
        String orgName = repositoryEvent.organization().getLogin();

        Log.debugf("[%s] collaborator change in %s", ME, repoFullName);

        // Always clear cache for modified collaborators
        refreshCollaborators(repoFullName);

        WatchedTeams watcher = orgWatchers.get(orgName);
        if (watcher == null) {
            return;
        }

        MembershipUpdate update = new MembershipUpdate(
                MembershipUpdateType.COLLABORATOR,
                orgName,
                repositoryEvent);

        // avoid conflicts: repos always use full name
        watcher.handleUpdate(repoFullName, update, periodicSync);
    }

    static class WatchedTeams {
        final String orgName;
        final long installationId;
        final Map<String, Set<TaskCallback<MembershipUpdate>>> watchedResources = new HashMap<>();

        public WatchedTeams(String orgName, long installationId) {
            this.orgName = orgName;
            this.installationId = installationId;
        }

        /**
         * @param resourceName Org-relative name for teams; full name for repositories
         * @param update Update event callback
         */
        public void add(String resourceName, TaskCallback<MembershipUpdate> callback) {
            watchedResources.computeIfAbsent(resourceName, k -> new HashSet<>())
                    .add(callback);
        }

        /**
         * @param resourceName Org-relative name for teams; full name for repositories
         * @param update Membership update event
         */
        public void handleUpdate(String resourceName, MembershipUpdate update, PeriodicUpdateQueue periodicSync) {
            Log.debugf("[%s-%s] team membership change in %s for %s %s", ME,
                    installationId, orgName, update.type(), resourceName);

            Set<TaskCallback<MembershipUpdate>> callbacks = watchedResources.getOrDefault(resourceName, Set.of());
            for (var callback : callbacks) {
                // Found an interesting file in the push event, queue an update
                periodicSync.queue(callback.taskGroupName(),
                        () -> callback.run(update));
            }
        }
    }

    /**
     * Hard-reset of the membership watcher.
     * This is useful for testing.
     */
    protected void reset() {
        orgWatchers.clear();
    }

    public boolean isWatching(String orgName) {
        return orgWatchers.containsKey(orgName);
    }

    void dumpWatcherState() {
        System.out.println("--------- MembershipWatcher state ---------");
        for (var entry : orgWatchers.entrySet()) {
            String repoName = entry.getKey();
            System.out.println("Repo: " + repoName);
            var watcher = entry.getValue();
            System.out.println("  Files watching:");
            for (var resource : watcher.watchedResources.entrySet()) {
                String name = resource.getKey();
                int callbackCount = resource.getValue().size();
                System.out.println("    " + name + " - " + callbackCount + " callbacks");
            }
        }
        System.out.println("------------------------------------");
    }

    public enum MembershipUpdateType {
        TEAM,
        COLLABORATOR
    }

    public static record MembershipUpdate(
            MembershipUpdateType type,
            String orgName,
            MembershipEvent membershipEvent) {

        public RepositoryEvent repositoryEvent() {
            if (type == MembershipUpdateType.COLLABORATOR) {
                return (RepositoryEvent) membershipEvent;
            }
            return null;
        }

        public TeamEvent teamEvent() {
            if (type == MembershipUpdateType.TEAM) {
                return (TeamEvent) membershipEvent;
            }
            return null;
        }
    }

    public static interface MembershipEvent {
        GitHub github();

        long installationId();

        GHOrganization organization();

        GHUser sender();

        ActionType actionType();

        EventType eventType();
    }

    public static record RepositoryEvent(
            GitHub github,
            long installationId,
            GHOrganization organization,
            GHRepository repository,
            GHUser sender,
            ActionType actionType,
            EventType eventType) implements MembershipEvent {
    }

    public static record TeamEvent(
            GitHub github,
            long installationId,
            GHOrganization organization,
            GHTeam team,
            GHUser sender,
            ActionType actionType,
            EventType eventType) implements MembershipEvent {
    }
}
