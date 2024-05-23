package org.commonhaus.automation.admin.github;

import java.io.IOException;
import java.util.Set;

import org.commonhaus.automation.admin.AdminConfig.MemberConfig;
import org.commonhaus.automation.github.context.ActionType;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.github.context.QueryContext;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.logging.Log;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public class AdminQueryContext extends QueryContext {
    private final GHRepository repository;

    private final String logId;
    private final MonitoredRepo repoConfig;

    public AdminQueryContext(AppContextService contextService, GHRepository repository, long installationId) {
        this(contextService, repository, installationId, null);
    }

    public AdminQueryContext(AppContextService contextService, GHRepository repository,
            MonitoredRepo repoConfig) {
        this(contextService, repository, repoConfig.installationId(), repoConfig);
    }

    public AdminQueryContext(AppContextService contextService, GHRepository repository,
            long installationId, MonitoredRepo repoConfig) {
        super(contextService, installationId);
        this.repository = repository;
        this.repoConfig = repoConfig;
        this.logId = repository.getFullName() + ":admin";
    }

    @Override
    public String getLogId() {
        return logId;
    }

    @Override
    public String getRepositoryId() {
        return repository.getNodeId();
    }

    @Override
    public GHRepository getRepository() {
        return repository;
    }

    public GHRepository getRepository(String repoName) {
        return execGitHubSync((gh, dryRun) -> gh.getRepository(repoName));
    }

    @Override
    public EventType getEventType() {
        return EventType.bot;
    }

    @Override
    public ActionType getActionType() {
        return ActionType.bot;
    }

    public AdminQueryContext addExisting(GitHub github) {
        super.addExisting(github);
        return this;
    }

    public AdminQueryContext addExisting(DynamicGraphQLClient graphQLClient) {
        super.addExisting(graphQLClient);
        return this;
    }

    @Override
    public GHOrganization getOrganization() {
        return getOrganization(repository.getOwnerName());
    }

    public GHOrganization getOrganization(String orgName) {
        return execGitHubSync((gh, dryRun) -> gh.getOrganization(orgName));
    }

    public JsonNode readSourceFile(GHRepository repo, String path) {
        GHContent content = execGitHubSync((gh, dryRun) -> repo.getFileContent(path));
        if (content == null || hasErrors()) {
            Log.warnf("readSourceFile: source file %s not found in repo %s", path, repo.getFullName());
            return null;
        }

        try {
            JsonNode node = parseFile(content);
            return node == null || node.isNull() ? null : node;
        } catch (IOException e) {
            logAndSendEmail("getTeamSource",
                    "Unable to read file %s from repo %s".formatted(path, repo.getFullName()),
                    e);
            return null;
        }
    }

    JsonNode parseFile(GHContent content) throws IOException {
        return AppContextService.yamlMapper().readTree(content.read());
    }

    public <T> T parseFile(GHContent content, Class<T> type) throws IOException {
        return AppContextService.yamlMapper().readValue(content.read(), type);
    }

    public <T> String writeValue(T user) throws IOException {
        return AppContextService.yamlMapper().writeValueAsString(user);
    }

    @Override
    public String[] getErrorAddresses() {
        String[] errorAddresses = repoConfig == null ? null : repoConfig.errorEmailAddress();
        return errorAddresses == null
                ? super.getErrorAddresses()
                : errorAddresses;
    }

    public String[] dryRunEmailAddress() {
        return repoConfig == null ? null : repoConfig.dryRunEmailAddress();
    }

    public boolean userIsKnown(String login, MemberConfig memberConfig) {
        if (memberConfig == null) {
            return true;
        }
        if (memberConfig.collaborators().isPresent()) {
            for (String repoName : memberConfig.collaborators().get()) {
                GHRepository repo = getRepository(repoName);
                Set<String> names = execGitHubSync((gh, dryRun) -> repo.getCollaboratorNames());
                if (names != null && names.contains(login)) {
                    return true;
                }
            }
        }
        if (memberConfig.organizations().isPresent()) {
            GHUser user = execGitHubSync((gh, dryRun) -> gh.getUser(login));
            for (String orgName : memberConfig.organizations().get()) {
                GHOrganization org = getOrganization(orgName);
                return org.hasMember(user);
            }
        }
        return false;
    }
}
