package org.commonhaus.automation.github.context;

import jakarta.json.JsonObject;

import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;

import io.quarkiverse.githubapp.GitHubEvent;
import io.quarkus.logging.Log;

public class EventData {
    final GitHubEvent event;
    final JsonObject jsonData;
    final GHEventPayload ghPayload;
    private final String logId;

    /** GHRepo / context of current request */
    final GHRepository repository;
    final GHOrganization organization;
    final GHAppInstallation installation;

    final ActionType actionType;
    final EventType eventType;
    final DataCommonItem commonItem;

    private EventPayload eventPayload;

    public EventData(GitHubEvent event, GHEventPayload payload) {
        this.event = event;
        this.ghPayload = payload;
        this.eventType = EventType.fromString(event.getEvent());
        this.actionType = ActionType.fromString(event.getAction());
        this.jsonData = JsonAttribute.unpack(event.getPayload());

        this.commonItem = switch (eventType) {
            case discussion, discussion_comment -> {
                yield JsonAttribute.discussion.commonItemFrom(jsonData);
            }
            case issue, issue_comment -> {
                yield JsonAttribute.issue.commonItemFrom(jsonData);
            }
            case pull_request, pull_request_review -> {
                yield JsonAttribute.pullRequest.commonItemFrom(jsonData);
            }
            default -> {
                Log.errorf("getTitle: DataCommonItem unsupported event type %s", eventType);
                yield null;
            }
        };

        if (payload != null) {
            this.repository = payload.getRepository();
            this.organization = payload.getOrganization();
            this.installation = payload.getInstallation();
        } else {
            this.repository = JsonAttribute.repository.repositoryFrom(jsonData);
            this.organization = JsonAttribute.organization.organizationFrom(jsonData);
            this.installation = JsonAttribute.installation.appInstallationFrom(jsonData);
        }

        this.logId = "%s:%s.%s%s".formatted(
                installation.getId(), eventType, actionType,
                repository == null
                        ? ""
                        : (":" + repository.getFullName() + (getNumber() >= 0 ? ("#" + getNumber()) : "")));
    }

    public String getLogId() {
        return logId;
    }

    public JsonObject getJsonData() {
        return jsonData;
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

    public String getRepoSlug() {
        return repository.getFullName();
    }

    public String getRepositoryId() {
        return repository.getNodeId();
    }

    public GHRepository getRepository() {
        return repository;
    }

    public GHOrganization getOrganization() {
        return organization;
    }

    public long getInstallationId() {
        return installation.getId();
    }

    @SuppressWarnings("unchecked")
    public <T extends GHEventPayload> T getGHEventPayload() {
        return (T) ghPayload;
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
    public String getNodeId() {
        return commonItem == null ? null : commonItem.id;
    }

    public String getNodeUrl() {
        return commonItem == null ? null : commonItem.url;
    }

    public String getTitle() {
        return commonItem == null ? null : commonItem.title;
    }

    public String getBody() {
        return commonItem == null ? "" : commonItem.body;
    }

    public int getNumber() {
        return commonItem == null ? -1 : commonItem.number;
    }

    public boolean isClosed() {
        return commonItem == null ? false : commonItem.closedAt != null;
    }
}
