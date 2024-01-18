package org.commonhaus.automation.github;

import java.io.IOException;

import jakarta.json.JsonObject;

import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import com.fasterxml.jackson.databind.ObjectReader;

public class WebHookBase {
    final static ObjectReader reader = GitHub.getMappingObjectReader();

    public final String action;
    public final GitHub github;
    public final GHRepository repository;
    public final GHOrganization organization;
    public final GHAppInstallation installation;
    public final Actor sender;

    public WebHookBase(GitHub github, JsonObject object) throws IOException {
        this.github = github;
        this.action = JsonAttribute.action.stringFrom(object);
        this.sender = JsonAttribute.sender.actorFrom(object);

        // Cross from JsonB to Jackson. YAY!
        this.repository = repositoryFrom(JsonAttribute.repository.stringifyNodeFrom(object));
        this.organization = organizationFrom(JsonAttribute.organization.stringifyNodeFrom(object));
        this.installation = appInstallationFrom(JsonAttribute.installation.stringifyNodeFrom(object));
    }

    public static GHRepository repositoryFrom(String repositoryObject) throws IOException {
        return reader.readValue(repositoryObject, GHRepository.class);
    }

    public static GHOrganization organizationFrom(String organizationObject) throws IOException {
        return reader.readValue(organizationObject, GHOrganization.class);
    }

    public static GHAppInstallation appInstallationFrom(String appInstallationObject) throws IOException {
        return reader.readValue(appInstallationObject, GHAppInstallation.class);
    }
}
