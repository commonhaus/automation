package org.commonhaus.automation.github;

import jakarta.json.JsonObject;

import org.commonhaus.automation.github.model.ActionType;
import org.commonhaus.automation.github.model.DataActor;
import org.commonhaus.automation.github.model.DataDiscussion;
import org.commonhaus.automation.github.model.EventPayload;
import org.commonhaus.automation.github.model.EventType;
import org.commonhaus.automation.github.model.JsonAttribute;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;

import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkus.logging.Log;

public class EventData {
    final GitHubEvent event;
    final JsonObject jsonData;
    final GHEventPayload ghPayload;

    /** GHRepo / context of current request */
    final GHRepository repository;
    final GHOrganization organization;
    final GHAppInstallation installation;

    final ActionType actionType;
    final EventType eventType;

    private DataActor sender;
    private GHUser ghSender;
    private EventPayload eventPayload;

    EventData(GitHubEvent event, GHEventPayload payload) {
        this.event = event;
        this.ghPayload = payload;
        this.eventType = EventType.fromString(event.getEvent());
        this.actionType = ActionType.fromString(event.getAction());
        this.jsonData = JsonAttribute.unpack(event.getPayload());

        if (payload != null) {
            this.repository = payload.getRepository();
            this.organization = payload.getOrganization();
            this.installation = payload.getInstallation();
            this.ghSender = payload.getSender();
        } else {
            this.sender = JsonAttribute.sender.actorFrom(jsonData);
            this.repository = JsonAttribute.repository.repositoryFrom(jsonData);
            this.organization = JsonAttribute.organization.organizationFrom(jsonData);
            this.installation = JsonAttribute.installation.appInstallationFrom(jsonData);
        }
    }

    public String getSenderLogin() {
        return ghSender == null
                ? sender.login
                : ghSender.getLogin();
    }

    public String getAction() {
        return event.getAction();
    }

    public ActionType getActionType() {
        return actionType;
    }

    public EventType getEventType() {
        return eventType;
    }

    public String getRepoOwner() {
        return repository.getOwnerName();
    }

    public String getRepoName() {
        return repository.getName();
    }

    public String getRepositoryId() {
        return repository.getNodeId();
    }

    public GHRepository getRepository() {
        return repository;
    }

    public long installationId() {
        return installation.getId();
    }

    public <T extends GHEventPayload> T getGHEventPayload(Class<T> type) {
        return type.cast(ghPayload);
    }

    @SuppressWarnings("unchecked")
    public <T extends EventPayload> T getEventPayload() {
        if (eventPayload == null) {
            eventPayload = eventType.getDataFrom(actionType, jsonData);
        }
        return (T) eventPayload;
    }

    /**
     * Notes:
     * - For a discussion or the discussion comment, it is the discussion id.
     *
     * @return the id of the primary item for this event
     */
    public String getLabelableId() {
        return switch (eventType) {
            case discussion, discussion_comment -> {
                EventPayload.DiscussionPayload payload = getEventPayload();
                DataDiscussion discussion = payload.discussion;
                yield discussion.id;
            }
            case pull_request -> {
                GHEventPayload.PullRequest payload = getGHEventPayload(GHEventPayload.PullRequest.class);
                yield payload.getPullRequest().getNodeId();
            }
            default -> {
                Log.errorf("EventData.getLabelableId: unsupported event type %s", eventType);
                yield null;
            }
        };
    }
}
