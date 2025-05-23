package org.commonhaus.automation.hk.github;

import org.commonhaus.automation.github.context.GitHubQueryContext;
import org.commonhaus.automation.hk.api.MemberSession;
import org.kohsuke.github.GitHub;

import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public class UserQueryContext extends GitHubQueryContext {

    final MemberSession session;
    final AppContextService ctx;

    protected UserQueryContext(AppContextService ctx, MemberSession session) {
        super(ctx, -1);
        this.session = session;
        this.ctx = ctx;
        withExisting(session.connection());
    }

    @Override
    public String getLogId() {
        return "user:" + session.login();
    }

    public UserQueryContext withExisting(GitHub github) {
        super.withExisting(github);
        return this;
    }

    public UserQueryContext withExisting(DynamicGraphQLClient graphQLClient) {
        super.withExisting(graphQLClient);
        return this;
    }

    @Override
    public GitHub getGitHub() {
        if (github == null) {
            try {
                github = MemberSession.getUserConnection(session.nodeId(), session.identity());
            } catch (Exception e) {
                logAndSendEmail(session.nodeId(), e);
            }
        }
        return github;
    }

    @Override
    public DynamicGraphQLClient getGraphQLClient() {
        return graphQLClient;
    }
}
