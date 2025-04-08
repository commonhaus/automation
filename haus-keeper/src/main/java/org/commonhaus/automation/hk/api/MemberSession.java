package org.commonhaus.automation.hk.api;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

import org.commonhaus.automation.github.context.BaseQueryCache;
import org.commonhaus.automation.hk.AdminDataCache;
import org.commonhaus.automation.hk.data.CommonhausUser;
import org.commonhaus.automation.hk.github.AppContextService;
import org.commonhaus.automation.hk.github.UserQueryContext;
import org.commonhaus.automation.hk.member.AccessRoleManager;
import org.commonhaus.automation.hk.member.MemberInfo;
import org.kohsuke.github.GHMyself;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;

import io.quarkus.logging.Log;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.oidc.UserInfo;
import io.quarkus.security.identity.SecurityIdentity;

public class MemberSession implements MemberInfo {

    static MemberSession getMemberSession(AppContextService ctx, UserInfo userInfo, SecurityIdentity identity) {
        // Lookup based on userInfo, replace with cached session data if present
        final MemberSession lookup = new MemberSession(userInfo, identity);

        MemberSession memberProfile = AdminDataCache.MEMBER_SESSION.computeIfAbsent(lookup.nodeId, (k) -> {
            lookup.getUserData();
            return lookup;
        });

        // Create/renew GitHub connection
        try {
            memberProfile.connection = getUserConnection(memberProfile.nodeId(), identity);
        } catch (IOException e) {
            memberProfile.connectionError = e;
        }
        return memberProfile;
    }

    /**
     * Create or renew a GitHub connection for a user.
     * May return null if connection can not be established
     *
     * @param nodeId User Node ID
     * @param identity Security Identity
     * @return GitHub connection or null
     * @throws IOException
     */
    public static GitHub getUserConnection(String nodeId, SecurityIdentity identity) throws IOException {
        GitHub connect = BaseQueryCache.getCachedUserConnection(nodeId);
        if (connect == null || !connect.isCredentialValid()) {
            AccessTokenCredential token = identity.getCredential(AccessTokenCredential.class);
            connect = new GitHubBuilder().withOAuthToken(token.getToken(), nodeId).build();
            BaseQueryCache.putCachedUserConnection(nodeId, connect);
        }
        return connect;
    }

    public static void updateUserConnection(String nodeId, GitHub gh) {
        BaseQueryCache.putCachedUserConnection(nodeId, gh);
    }

    private final String nodeId;
    private final UserInfo userInfo;

    private transient GitHub connection;
    private transient IOException connectionError;
    private transient SecurityIdentity identity;
    private LoggedInUser userData;

    private MemberSession(UserInfo userInfo, SecurityIdentity identity) {
        this.userInfo = userInfo;
        this.nodeId = userInfo.getString("node_id");
        this.identity = identity;
    }

    public boolean userIsKnown(AppContextService ctx, AccessRoleManager roleManager) {
        UserQueryContext userQc = ctx.newUserQueryContext(this);
        return roleManager.userIsKnown(userQc, this);
    }

    public LoggedInUser getUserData() {
        LoggedInUser user = userData;
        if (user == null) {
            userData = user = new LoggedInUser(info().getJsonObject());
        }
        return user;
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

    public String url() {
        return getUserData().url;
    }

    public LoggedInUser updateApplication(CommonhausUser user) {
        LoggedInUser userData = this.getUserData();
        userData.hasApplication = user.hasApplication();
        return userData;
    }

    public GitHub connection() {
        return connection;
    }

    public boolean hasConnection() {
        return connection != null;
    }

    public Throwable connectionError() {
        return connectionError;
    }

    public SecurityIdentity identity() {
        return identity;
    }

    public Optional<String> notificationEmail() {
        // non-fatal. Proceed, but notification can't be sent to user
        try {
            GHMyself myself = connection().getMyself();
            return myself.getEmails2().stream()
                    .filter(x -> x.isPrimary())
                    .map(x -> x.getEmail())
                    .findFirst();
        } catch (IOException e) {
            Log.errorf(e, "getNotificationEmail: Failed to get primary email for %s", login());
        }
        return Optional.empty();
    }
}
