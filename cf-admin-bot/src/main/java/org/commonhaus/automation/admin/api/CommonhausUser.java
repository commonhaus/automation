package org.commonhaus.automation.admin.api;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.annotation.Nonnull;
import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.admin.github.AppContextService;
import org.commonhaus.automation.admin.github.DatastoreQueryContext;
import org.commonhaus.automation.github.context.DataCommonItem;
import org.kohsuke.github.GHContent;

import com.fasterxml.jackson.annotation.JsonAlias;
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

    @SuppressWarnings("unused")
    public static class Discord {
        String id;
        String username;
        String discriminator;
        boolean verified;
    }

    public static class ForwardEmail {
        /** Is an alias active for this user */
        @JsonAlias({ "active", "configured" })
        boolean hasDefaultAlias;

        /** Additional ForwardEmail aliases. Optional and rare. */
        @JsonAlias("alt_alias")
        List<String> altAlias;

        public Collection<? extends String> altAlias() {
            return altAlias == null ? List.of() : altAlias;
        }
    }

    @SuppressWarnings("unused")
    public static class Services {
        @JsonAlias("forward_email")
        ForwardEmail forwardEmail;
        Discord discord;

        public ForwardEmail forwardEmail() {
            if (forwardEmail == null) {
                forwardEmail = new ForwardEmail();
            }
            return forwardEmail;
        }

        public Discord discord() {
            if (discord == null) {
                discord = new Discord();
            }
            return discord;
        }
    }

    @SuppressWarnings("unused")
    public static class GoodStanding {
        Map<String, Attestation> attestation = new HashMap<>();
        String contribution;
        String dues;

        public Map<String, Attestation> attestation() {
            if (attestation == null) {
                attestation = new HashMap<>();
            }
            return attestation;
        }
    }

    public record AttestationPost(
            @Nonnull String id,
            @Nonnull String version) {
    }

    public record Attestation(
            @Nonnull @JsonAlias("with_status") MemberStatus withStatus,
            @Nonnull String date,
            @Nonnull String version) {
    }

    public static class Data {
        @Nonnull
        MemberStatus status = MemberStatus.UNKNOWN;

        @JsonAlias("good_until")
        GoodStanding goodUntil = new GoodStanding();

        Services services = new Services();
    }

    public record MembershipApplication(
            @Nonnull String nodeId,
            @Nonnull String htmlUrl) {

        static MembershipApplication fromDataCommonType(DataCommonItem data) {
            return new MembershipApplication(data.id, data.url);
        }
    }

    @Nonnull
    final String login;
    @Nonnull
    final long id;
    @Nonnull
    final Data data;
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
        this.data = builder.data == null ? new Data() : builder.data;
        this.history = builder.history == null ? new ArrayList<>() : builder.history;
        this.application = builder.application;
        this.isMember = builder.isMember;
        this.statusChange = builder.statusChange;
    }

    private CommonhausUser(String login, long id) {
        this.login = login;
        this.id = id;
        this.data = new Data();
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

    public Services services() {
        if (data.services == null) {
            data.services = new Services();
        }
        return data.services;
    }

    public String sha() {
        return sha;
    }

    public MemberStatus status() {
        return data.status;
    }

    public void status(MemberStatus status) {
        data.status = status;
        statusChange = now();
    }

    public void sha(String sha) {
        this.sha = sha;
    }

    public GoodStanding goodUntil() {
        if (data.goodUntil == null) {
            data.goodUntil = new GoodStanding();
        }
        return data.goodUntil;
    }

    public boolean postConflict() {
        return conflict;
    }

    public void setConflict(boolean conflict) {
        this.conflict = conflict;
    }

    public MembershipApplication application() {
        return application;
    }

    public void application(MembershipApplication application) {
        this.application = application;
    }

    public void addHistory(String message) {
        history.add("%s %s".formatted(now(), message));
    }

    public boolean emptyMember() {
        return isMember == null;
    }

    @JsonIgnore
    public boolean isMember() {
        return data.status.couldBeActiveMember()
                && (isMember != null && isMember);
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
        if (newStatus == MemberStatus.UNKNOWN && !roles.isEmpty()) {
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
    boolean updateMemberStatus(AppContextService ctx, Set<String> roles) {
        MemberStatus oldStatus = data.status;
        data.status = refreshStatus(ctx, roles, data.status);
        if (oldStatus != data.status) {
            statusChange = now();
            return true;
        }
        return false;
    }

    public boolean hasApplication() {
        return application != null;
    }

    @JsonIgnore
    public boolean isNew() {
        return history.isEmpty();
    }

    public ApiResponse toResponse() {
        return new ApiResponse(ApiResponse.Type.HAUS, data)
                .responseStatus(postConflict() ? Response.Status.CONFLICT : Response.Status.OK);
    }

    @Override
    public String toString() {
        return "CommonhausUser [login=" + login + ", id=" + id + ", sha=" + sha + ", conflict=" + conflict + "]";
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
        user.data.status = MemberStatus.UNKNOWN;
        return user;
    }

    @SuppressWarnings("unused")
    public static class Builder {
        private String login;
        private long id;
        private Data data;
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

        public Builder withData(Data data) {
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
