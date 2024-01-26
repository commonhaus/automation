package org.commonhaus.automation.github;

import jakarta.json.JsonObject;

import org.commonhaus.automation.github.model.Actor;
import org.commonhaus.automation.github.model.JsonAttribute;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHEvent;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;

import io.quarkiverse.githubapp.GitHubEvent;

public class EventData {
    public final GitHubEvent event;
    public final JsonObject jsonPayload;
    public final GHEventPayload ghPayload;

    /** GHRepo / context of current request */
    public final GHRepository repository;
    public final GHOrganization organization;
    public final GHAppInstallation installation;

    public final GHEvent eventType;

    private Actor sender;
    private GHUser ghSender;

    EventData(GitHubEvent event, GHEventPayload payload) {
        this.event = event;
        this.ghPayload = payload;
        this.eventType = toEventType(event.getEvent());
        this.jsonPayload = JsonAttribute.unpack(event.getPayload());

        if (payload != null) {
            this.repository = payload.getRepository();
            this.organization = payload.getOrganization();
            this.installation = payload.getInstallation();
            this.ghSender = payload.getSender();
        } else {
            this.sender = JsonAttribute.sender.actorFrom(jsonPayload);
            this.repository = JsonAttribute.repository.repositoryFrom(jsonPayload);
            this.organization = JsonAttribute.organization.organizationFrom(jsonPayload);
            this.installation = JsonAttribute.installation.appInstallationFrom(jsonPayload);
        }
    }

    private GHEvent toEventType(String type) {
        if (type != null) {
            try {
                return GHEvent.valueOf(type.toUpperCase());
            } catch (IllegalArgumentException iae) {
                // ignore
            }
        }
        return GHEvent.UNKNOWN;
    }

    public Object getRepoOwner() {
        return repository.getOwnerName();
    }

    public Object getRepoName() {
        return repository.getName();
    }

    public long installationId() {
        return installation.getId();
    }

    public <T extends GHEventPayload> T getEventPayload(Class<T> type) {
        return type.cast(ghPayload);
    }
}
