package org.commonhaus.automation.github.watchers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.commonhaus.automation.github.context.ActionType;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.github.watchers.FileWatcher.FilePushEvent;
import org.commonhaus.automation.github.watchers.MembershipWatcher.RepositoryEvent;
import org.commonhaus.automation.github.watchers.MembershipWatcher.TeamEvent;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkiverse.githubapp.event.Member;
import io.quarkiverse.githubapp.event.Membership;
import io.quarkiverse.githubapp.event.Push;
import io.quarkiverse.githubapp.event.Team;

/**
 * GitHub App will transform this into a multiplexed bean for
 * event handling...
 */
@ApplicationScoped
public class GitHubEventHandler {

    @Inject
    FileWatcher fileWatcher;

    @Inject
    MembershipWatcher membershipWatcher;

    /**
     * Check for push to watched file
     *
     * @param event
     * @param github
     * @param pushEvent
     */
    public void handlePushEvent(GitHubEvent event, GitHub github,
            @Push GHEventPayload.Push pushEvent) {

        GHRepository repo = pushEvent.getRepository();
        FilePushEvent fileEvent = new FileWatcher.FilePushEvent(
                pushEvent,
                event.getInstallationId(),
                repo,
                pushEvent.getSender(),
                github);
        fileWatcher.handleEvent(fileEvent);
    }

    /**
     * An event fired due to activity relating to team membership
     *
     * - An organization member was added to a team.
     * - An organization member was removed from a team.
     *
     * Primary attributes of the webhook: organization, team, member
     *
     * @param event
     * @param github
     * @param payload
     */
    public void updateTeamMembership(GitHubEvent event, GitHub github,
            @Membership GHEventPayload.Membership payload) {
        long installationId = payload.getInstallation().getId();

        TeamEvent teamEvent = new TeamEvent(
                github,
                installationId,
                payload.getOrganization(),
                payload.getTeam(),
                payload.getSender(),
                ActionType.fromString(event.getAction()),
                EventType.fromString(event.getEvent()));
        membershipWatcher.handleTeamEvent(teamEvent);
    }

    /**
     * An event fired due to activity relating to a repository member (collaborator)
     *
     * - An collaborator was added to a team.
     * - An collaborator was removed from a team.
     *
     * @param event
     * @param github
     * @param payload
     */
    public void updateMember(GitHubEvent event, GitHub github,
            @Member GHEventPayload.Member payload) {
        long installationId = payload.getInstallation().getId();

        RepositoryEvent repositoryEvent = new RepositoryEvent(
                github,
                installationId,
                payload.getOrganization(),
                payload.getRepository(),
                payload.getSender(),
                ActionType.fromString(event.getAction()),
                EventType.fromString(event.getEvent()));
        membershipWatcher.handleCollaboratorEvent(repositoryEvent);
    }

    /**
     * An event fired due to activity relating to an organization team
     *
     * - A team was created
     * - A team was deleted
     * - A team was edited
     * - A team was added to a repository
     * - A team was removed from a repository
     *
     * @param event
     * @param github
     * @param payload
     */
    public void updateTeam(GitHubEvent event, GitHub github,
            @Team GHEventPayload.Team payload) {
        long installationId = payload.getInstallation().getId();

        TeamEvent teamEvent = new TeamEvent(
                github,
                installationId,
                payload.getOrganization(),
                payload.getTeam(),
                payload.getSender(),
                ActionType.fromString(event.getAction()),
                EventType.fromString(event.getEvent()));
        membershipWatcher.handleTeamEvent(teamEvent);
    }
}
