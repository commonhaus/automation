package org.commonhaus.automation.admin.github;

import org.commonhaus.automation.admin.api.MemberSession;
import org.commonhaus.automation.github.context.QueryContext;
import org.kohsuke.github.GitHub;

import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

public class UserQueryContext extends QueryContext {

    final MemberSession session;

    protected UserQueryContext(AppContextService contextService, MemberSession session) {
        super(contextService, -1);
        addExisting(session.connection());
        this.session = session;
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
        // NO-OP. No installation Id, can't renew this way
    }
}
