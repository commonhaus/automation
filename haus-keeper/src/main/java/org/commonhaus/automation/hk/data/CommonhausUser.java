package org.commonhaus.automation.hk.data;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import jakarta.annotation.Nonnull;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.hk.data.CommonhausUserData.GoodStanding;
import org.commonhaus.automation.hk.data.CommonhausUserData.Services;
import org.commonhaus.automation.hk.github.AppContextService;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Commonhaus user: stored as json file
 */
@RegisterForReflection
@JsonDeserialize(builder = CommonhausUser.Builder.class)
public class CommonhausUser implements UserLogin {
    public static final String MEMBER_ROLE = "member";

    @Nonnull
    String login;
    @Nonnull
    final long id;
    @Nonnull
    final CommonhausUserData data;
    @Nonnull
    final Set<String> history = new TreeSet<>();

    Boolean isMember;
    String statusChange;

    @JsonProperty("application")
    ApplicationIssue appIssue;

    transient String sha = null;
    transient boolean conflict = false;

    private CommonhausUser(Builder builder) {
        this.login = builder.login;
        this.id = builder.id;
        this.data = builder.data == null ? new CommonhausUserData() : builder.data;
        this.history.addAll(builder.history);
        this.appIssue = builder.appIssue;
        this.isMember = builder.isMember;
        this.statusChange = builder.statusChange;
    }

    private CommonhausUser(String login, long id) {
        this.login = login;
        this.id = id;
        this.data = new CommonhausUserData();
        this.isMember = null;
        this.statusChange = null;
    }

    public String login() {
        return login;
    }

    // Possible! Rare, but has happened
    public void changeLogin(String login) {
        this.login = login;
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
        return appIssue != null;
    }

    public ApplicationIssue application() {
        return appIssue;
    }

    public void setAppIssue(ApplicationIssue application) {
        this.appIssue = application;
    }

    public void addHistory(String message) {
        history.add("%s %s".formatted(now(), message));
    }

    public void addProject(String projectName) {
        this.data.projects.add(projectName);
    }

    public Collection<String> projects() {
        return data.projects();
    }

    public boolean aliasesMatch(String projectName, String projectDomain, Set<String> newAliases) {
        var currentAliases = services().forwardEmail.altAlias().stream()
                .filter(a -> a.endsWith("@" + projectDomain))
                .collect(Collectors.toSet());
        var expectedAliases = new HashSet<>(newAliases);

        // Test to see if user record is already up to date
        return projects().contains(projectName)
                && currentAliases.equals(expectedAliases);
    }

    @JsonIgnore
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
        data.services.forwardEmail().toggleDefaultAlias(status);
        statusChange = now();
        return true;
    }

    /**
     * Merge another user into this one.
     * This is used to resolve pending updates after a restart.
     *
     * @param other
     */
    public void merge(CommonhausUser other) {
        if (other == null) {
            return;
        }

        // Do not merge sha or conflict flags.
        // Those will have to be refreshed when the latest data is retrieved from GitHub

        this.appIssue = other.appIssue;
        this.isMember = other.isMember;
        this.statusChange = other.statusChange;
        if (other.data != null) {
            this.data.merge(other.data);
        }
        // Make sure default alias status is set
        data.services.forwardEmail().toggleDefaultAlias(data.status);

        // Merge history, no duplicates
        this.history.addAll(other.history);
    }

    MemberStatus refreshStatus(AppContextService ctx, Set<String> roles, MemberStatus oldStatus) {
        MemberStatus newStatus = oldStatus;
        if (isMember() && !roles.contains(MEMBER_ROLE)) {
            // inconsistency: user is a member but does not have the member role
            // this could be a missed automation step
            ctx.logAndSendEmail("refreshStatus",
                    "refreshStatus: %s is a member but does not have the member role (missing group?)".formatted(login()),
                    null);
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
        return "CommonhausUser [login=%s, id=%s, sha=%s, conflict=%s, status=%s, application=%s]"
                .formatted(login, id, sha, conflict, status(), application());
    }

    private String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.MINUTES));
    }

    public static CommonhausUser create(String login, long id) {
        CommonhausUser user = new CommonhausUser(login, id);
        return user;
    }

    public static class Builder {
        private String login;
        private long id;
        private CommonhausUserData data;
        private Boolean isMember;
        private String statusChange;

        private ApplicationIssue appIssue;
        private List<String> history = new ArrayList<>();

        public Builder copy(CommonhausUser user) {
            this.login = user.login;
            this.id = user.id;
            this.isMember = user.isMember;
            this.statusChange = user.statusChange;
            this.appIssue = user.appIssue;
            this.history.addAll(user.history);

            this.data = new CommonhausUserData();
            this.data.merge(user.data);
            return this;
        }

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

        public Builder withHistory(Collection<String> history) {
            this.history.addAll(history);
            return this;
        }

        public Builder withApplication(ApplicationIssue appIssue) {
            this.appIssue = appIssue;
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
