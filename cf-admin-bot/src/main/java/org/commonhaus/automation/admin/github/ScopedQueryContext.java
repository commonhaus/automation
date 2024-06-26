package org.commonhaus.automation.admin.github;

import java.io.IOException;

import jakarta.annotation.Nonnull;

import org.commonhaus.automation.github.context.ActionType;
import org.commonhaus.automation.github.context.EventType;
import org.commonhaus.automation.github.context.QueryContext;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.logging.Log;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public class ScopedQueryContext extends QueryContext {
    private String ownerName;
    private String repoFullName;

    private GHRepository repository;
    private MonitoredRepo repoConfig;

    /**
     * In this path, repository could be null (org-level event)
     *
     * @param contextService
     * @param installationId
     * @param orgName
     * @param repoFullName
     */
    public ScopedQueryContext(@Nonnull AppContextService contextService, @Nonnull long installationId, @Nonnull String orgName,
            String repoFullName) {
        super(contextService, installationId);
        this.ownerName = orgName;
        this.repoFullName = repoFullName;
    }

    public ScopedQueryContext(@Nonnull AppContextService contextService, @Nonnull long installationId,
            @Nonnull GHRepository repo) {
        super(contextService, installationId);
        this.repoFullName = repo.getFullName();
        this.ownerName = QueryContext.toOrganizationName(repoFullName);
        this.repository = repo;
    }

    /**
     * In this path, repository could be null (org-level event)
     *
     * @param contextService
     * @param installationId
     * @param org GHOrganization
     * @param repo GHRepository
     */
    public ScopedQueryContext(@Nonnull AppContextService contextService, @Nonnull long installationId,
            @Nonnull GHOrganization org, GHRepository repo) {
        super(contextService, installationId);
        this.ownerName = org.getLogin();
        this.repository = repo;
    }

    public ScopedQueryContext addExisting(MonitoredRepo repoCfg) {
        this.repoConfig = repoCfg;
        return this;
    }

    @Override
    public String getRepositoryId() {
        GHRepository repo = getRepository();
        return repo == null ? null : getRepository().getNodeId();
    }

    @Override
    public GHRepository getRepository() {
        if (repository == null && repoFullName != null) {
            repository = getRepository(repoFullName);
        }
        return repository;
    }

    @Override
    public EventType getEventType() {
        return EventType.bot;
    }

    @Override
    public ActionType getActionType() {
        return ActionType.bot;
    }

    public ScopedQueryContext addExisting(GitHub github) {
        super.addExisting(github);
        return this;
    }

    public ScopedQueryContext addExisting(DynamicGraphQLClient graphQLClient) {
        super.addExisting(graphQLClient);
        return this;
    }

    @Override
    public GHOrganization getOrganization() {
        return getOrganization(ownerName);
    }

    public JsonNode readSourceFile(GHRepository repo, String path) {
        GHContent content = execGitHubSync((gh, dryRun) -> repo.getFileContent(path));
        if (content == null || hasErrors()) {
            clearNotFound();
            Log.warnf("readSourceFile: source file %s not found in repo %s", path, repo.getFullName());
            return null;
        }

        try {
            JsonNode node = parseFile(content);
            return node == null || node.isNull() ? null : node;
        } catch (IOException e) {
            logAndSendEmail("readSourceFile",
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
}
