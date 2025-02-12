package org.commonhaus.automation.admin.data;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jakarta.annotation.Nonnull;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.admin.data.CommonhausUserData.GoodStanding;
import org.commonhaus.automation.admin.data.CommonhausUserData.Services;
import org.commonhaus.automation.admin.github.AppContextService;
import org.commonhaus.automation.admin.github.DatastoreQueryContext;
import org.kohsuke.github.GHContent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Commonhaus user: stored as json file
 */
@RegisterForReflection
@JsonDeserialize(builder = CommonhausUser.Builder.class)
public class CommonhausUser {
    public static final String MEMBER_ROLE = "member";

    @Nonnull
    final String login;
    @Nonnull
    final long id;
    @Nonnull
    final CommonhausUserData data;
    @Nonnull
    final List<String> history;

    Boolean isMember;
    String statusChange;
    MembershipApplication application;

    transient String sha = null;
    transient boolean conflict = false;

    private CommonhausUser(Builder builder) {
        this.login = builder.login;
        this.id = builder.id;
        this.data = builder.data == null ? new CommonhausUserData() : builder.data;
        this.history = builder.history == null ? new ArrayList<>() : builder.history;
        this.application = builder.application;
        this.isMember = builder.isMember;
        this.statusChange = builder.statusChange;
    }

    private CommonhausUser(String login, long id) {
        this.login = login;
        this.id = id;
        this.data = new CommonhausUserData();
        this.history = new ArrayList<>();
        this.isMember = null;
        this.statusChange = null;
    }

    public String login() {
        return login;
    }

    public long id() {
        return id;
    }

    @JsonIgnore
    public boolean isNew() {
        return history.isEmpty();
    }

    public Services services() {
        if (data.services == null) {
            data.services = new Services();
        }
        return data.services;
    }

    public String sha() {
        return sha;
    }

    public void sha(String sha) {
        this.sha = sha;
    }

    public boolean conflictOccurred() {
        return conflict;
    }

    public void setConflict(boolean conflict) {
        this.conflict = conflict;
    }

    public GoodStanding goodUntil() {
        if (data.goodUntil == null) {
            data.goodUntil = new GoodStanding();
        }
        return data.goodUntil;
    }

    public boolean hasApplication() {
        return application != null;
    }

    public MembershipApplication application() {
        return application;
    }

    public void setApplication(MembershipApplication application) {
        this.application = application;
    }

    public void addHistory(String message) {
        history.add("%s %s".formatted(now(), message));
    }

    public boolean isMemberUndefined() {
        return isMember == null;
    }

    @JsonIgnore
    public boolean isMember() {
        return data.status.couldBeActiveMember()
                && (isMember != null && isMember);
    }

    public void setIsMember(boolean isMember) {
        this.isMember = isMember;
    }

    public MemberStatus status() {
        return data.status;
    }

    public boolean setStatus(MemberStatus status) {
        if (data.status == status) {
            return false;
        }
        data.status = status;
        statusChange = now();
        return true;
    }

    MemberStatus refreshStatus(AppContextService ctx, Set<String> roles, MemberStatus oldStatus) {
        MemberStatus newStatus = oldStatus;
        if (isMember() && !roles.contains(MEMBER_ROLE)) {
            // inconsistency: user is a member but does not have the member role
            // this could be a missed automation step
            ctx.logAndSendEmail("refreshStatus",
                    "refreshStatus: %s is a member but does not have the member role (missing group?)".formatted(login()),
                    null, null);
            roles.add(MEMBER_ROLE);
        }
        if (!roles.isEmpty()) {
            List<MemberStatus> status = roles.stream()
                    .map(r -> ctx.getStatusForRole(r))
                    .sorted()
                    .toList();
            newStatus = status.get(0);
        }
        return newStatus;
    }

    // read-only: test for change in status value
    public boolean statusUpdateRequired(AppContextService ctx, Set<String> roles) {
        MemberStatus newStatus = refreshStatus(ctx, roles, data.status);
        return data.status != newStatus;
    }

    // update user status
    public boolean updateMemberStatus(AppContextService ctx, Set<String> roles) {
        MemberStatus newStatus = refreshStatus(ctx, roles, data.status);
        return setStatus(newStatus);
    }

    public ApiResponse toResponse() {
        return new ApiResponse(ApiResponse.Type.HAUS, data)
                .responseStatus(conflictOccurred() ? Response.Status.CONFLICT : Response.Status.OK);
    }

    @Override
    public String toString() {
        return "CommonhausUser [login=%s, id=%s, sha=%s, conflict=%s, status=%s]"
                .formatted(login, id, sha, conflict, status());
    }

    private String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.MINUTES));
    }

    public static CommonhausUser parseFile(DatastoreQueryContext dqc, GHContent content) throws IOException {
        CommonhausUser user = dqc.parseYamlFile(content, CommonhausUser.class);
        if (user != null) {
            user.sha = content.getSha();
        }
        return user;
    }

    public static CommonhausUser create(String login, long id) {
        CommonhausUser user = new CommonhausUser(login, id);
        return user;
    }

    @SuppressWarnings("unused")
    public static class Builder {
        private String login;
        private long id;
        private CommonhausUserData data;
        private Boolean isMember;
        private String statusChange;

        private MembershipApplication application;
        private List<String> history;

        public Builder withLogin(String login) {
            this.login = login;
            return this;
        }

        public Builder withId(long id) {
            this.id = id;
            return this;
        }

        public Builder withData(CommonhausUserData data) {
            this.data = data;
            return this;
        }

        public Builder withHistory(List<String> history) {
            this.history = history;
            return this;
        }

        public Builder withApplication(MembershipApplication application) {
            this.application = application;
            return this;
        }

        public Builder withIsMember(Boolean isMember) {
            this.isMember = isMember;
            return this;
        }

        public Builder withStatusChange(String statusChange) {
            this.statusChange = statusChange;
            return this;
        }

        public CommonhausUser build() {
            return new CommonhausUser(this);
        }
    }
}
