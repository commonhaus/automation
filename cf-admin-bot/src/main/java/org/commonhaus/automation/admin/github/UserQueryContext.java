package org.commonhaus.automation.admin.github;

import java.io.IOException;

import org.commonhaus.automation.admin.api.MemberSession;
import org.commonhaus.automation.github.context.QueryContext;
import org.kohsuke.github.GitHub;

import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public class UserQueryContext extends QueryContext {

    final MemberSession session;
    final AppContextService ctx;

    protected UserQueryContext(AppContextService ctx, MemberSession session) {
        super(ctx, -1);
        addExisting(session.connection());
        this.session = session;
        this.ctx = ctx;
    }

    @Override
    public String getLogId() {
        return "user:" + session.login();
    }

    public UserQueryContext addExisting(GitHub github) {
        super.addExisting(github);
        return this;
    }

    public UserQueryContext addExisting(DynamicGraphQLClient graphQLClient) {
        super.addExisting(graphQLClient);
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

    @Override
    public void refreshConnection() {
        try {
            ctx.getUserConnection(session.nodeId(), session.identity());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to create a GitHub client for the user " + session.login(), e);
        }
    }
}
