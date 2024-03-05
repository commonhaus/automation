package org.commonhaus.automation.github;

import java.time.Instant;

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
    private final String logId;

    /** GHRepo / context of current request */
    final GHRepository repository;
    final GHOrganization organization;
    final GHAppInstallation installation;

    final ActionType actionType;
    final EventType eventType;

    private DataActor sender;
    private GHUser ghSender;
    private EventPayload eventPayload;
    private String nodeId;
    private String nodeUrl;
    private String body;
    private int number = 0;

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

        this.logId = "("
                + getRepoSlug() + "::"
                + getEventType() + "."
                + getActionType()
                + (getNumber() >= 0 ? ("#" + getNumber()) : "")
                + ")";
    }

    public String getLogId() {
        return logId;
    }

    public JsonObject getJsonData() {
        return jsonData;
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

    public String eventTime() {
        return Instant.now().toString();
    }

    public String getRepoOwner() {
        return repository.getOwnerName();
    }

    public String getRepoName() {
        return repository.getName();
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

    public long installationId() {
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
        String id = nodeId;
        if (id == null) {
            id = nodeId = switch (eventType) {
                case discussion, discussion_comment -> {
                    EventPayload.DiscussionPayload payload = getEventPayload();
                    DataDiscussion discussion = payload.discussion;
                    yield discussion.id;
                }
                case pull_request -> {
                    GHEventPayload.PullRequest payload = getGHEventPayload();
                    yield payload.getPullRequest().getNodeId();
                }
                default -> {
                    Log.errorf("[%s] EventData.getNodeId: unsupported event type", logId);
                    yield null;
                }
            };
        }
        return id;
    }

    public String getNodeUrl() {
        String url = nodeUrl;
        if (url == null) {
            url = nodeUrl = switch (eventType) {
                case discussion, discussion_comment -> {
                    EventPayload.DiscussionPayload payload = getEventPayload();
                    DataDiscussion discussion = payload.discussion;
                    yield discussion.url;
                }
                case issue, issue_comment -> {
                    GHEventPayload.Issue payload = getGHEventPayload();
                    yield payload.getIssue().getHtmlUrl().toString();
                }
                case pull_request -> {
                    GHEventPayload.PullRequest payload = getGHEventPayload();
                    yield payload.getPullRequest().getHtmlUrl().toString();
                }
                default -> {
                    Log.errorf("[%s] EventData.getNodeUrl: unsupported event type", logId);
                    yield null;
                }
            };
        }
        return url;
    }

    public String getBody() {
        String result = body;
        if (result == null) {
            result = body = switch (eventType) {
                case discussion -> {
                    EventPayload.DiscussionPayload payload = getEventPayload();
                    DataDiscussion discussion = payload.discussion;
                    yield discussion.body;
                }
                case pull_request -> {
                    GHEventPayload.PullRequest payload = getGHEventPayload();
                    yield payload.getPullRequest().getBody();
                }
                default -> {
                    Log.errorf("[%s] EventData.getBody: unsupported event type", logId);
                    yield null;
                }
            };
        }
        return result;
    }

    public int getNumber() {
        int result = number;
        if (result == 0) {
            result = number = switch (eventType) {
                case discussion, discussion_comment -> {
                    EventPayload.DiscussionPayload payload = getEventPayload();
                    DataDiscussion discussion = payload.discussion;
                    yield discussion.number;
                }
                case issue -> {
                    GHEventPayload.Issue payload = getGHEventPayload();
                    yield payload.getIssue().getNumber();
                }
                case issue_comment -> {
                    GHEventPayload.IssueComment payload = getGHEventPayload();
                    yield payload.getIssue().getNumber();
                }
                case pull_request -> {
                    GHEventPayload.PullRequest payload = getGHEventPayload();
                    yield payload.getPullRequest().getNumber();
                }
                case label -> -1;
            };
        }
        return result;
    }
}
