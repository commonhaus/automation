package org.commonhaus.automation.admin.api;

import java.io.IOException;
import java.util.Set;

import org.commonhaus.automation.admin.AdminDataCache;
import org.commonhaus.automation.admin.github.AppContextService;
import org.kohsuke.github.GHMyself;
import org.kohsuke.github.GitHub;

import io.quarkus.logging.Log;
import io.quarkus.oidc.UserInfo;
import io.quarkus.security.identity.SecurityIdentity;

public class MemberSession {

    static MemberSession getMemberSession(AppContextService ctx, UserInfo userInfo, SecurityIdentity identity) {
        // Lookup based on userInfo, replace with cached session data if present
        final MemberSession lookup = new MemberSession(userInfo);

        MemberSession memberProfile = AdminDataCache.MEMBER_SESSION.computeIfAbsent(lookup.nodeId, (k) -> {
            lookup.getUserData();
            return lookup;
        });

        // Create/renew GitHub connection
        memberProfile.connect(ctx, identity);
        return memberProfile;
    }

    private final String nodeId;
    private final UserInfo userInfo;

    private GitHub connection;
    private GHMyself myself;
    private GitHubUser userData;

    private MemberSession(UserInfo userInfo) {
        this.userInfo = userInfo;
        this.nodeId = userInfo.getString("node_id");
    }

    public GitHub connect(AppContextService ctx, SecurityIdentity identity) {
        if (connection == null) {
            connection = ctx.getConnection(nodeId, identity);
        }
        return connection;
    }

    public GitHubUser getUserData() {
        GitHubUser user = userData;
        if (user == null) {
            userData = user = new GitHubUser(info().getJsonObject());
        }
        return user;
    }

    public GHMyself getMyself() {
        if (connection == null) {
            return null;
        }
        if (myself == null) {
            try {
                myself = connection.getMyself();
                GitHubUser data = getUserData();
                data.name = myself.getName();
                data.company = myself.getCompany();
            } catch (IOException e) {
                Log.errorf(e, "Unable to retrieve user information for %s", getUserData().login);
                connection = null;
            }
        }
        return myself;
    }

    public GitHub connection() {
        return connection;
    }

    public boolean hasConnection() {
        return connection != null;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((nodeId == null) ? 0 : nodeId.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        MemberSession other = (MemberSession) obj;
        if (nodeId == null) {
            if (other.nodeId != null)
                return false;
        } else if (!nodeId.equals(other.nodeId))
            return false;
        return true;
    }

    @Override
    public String toString() {
        return "MemberSessionData [nodeId=" + nodeId
                + ", userInfo=" + userInfo
                + ", userData=" + userData
                + "]";
    }

    UserInfo info() {
        return userInfo;
    }

    public String nodeId() {
        return nodeId;
    }

    public long id() {
        return getUserData().id;
    }

    public String login() {
        return getUserData().login;
    }

    public String name() {
        return getUserData().name;
    }

    public Set<String> roles() {
        return getUserData().roles;
    }
}
