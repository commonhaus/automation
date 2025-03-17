package org.commonhaus.automation.hk.github;

import org.commonhaus.automation.github.context.QueryContext;
import org.commonhaus.automation.hk.api.MemberSession;
import org.kohsuke.github.GitHub;

import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public class UserQueryContext extends QueryContext {

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
        return github;
    }

    @Override
    public DynamicGraphQLClient getGraphQLClient() {
        return graphQLClient;
    }
}
