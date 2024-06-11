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

import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.admin.github.AppContextService;
import org.commonhaus.automation.admin.github.ScopedQueryContext;
import org.commonhaus.automation.github.context.DataCommonItem;
import org.kohsuke.github.GHContent;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.smallrye.common.constraint.NotNull;

/**
 * Commonhaus user: stored as json file
 */
@RegisterForReflection
@JsonDeserialize(builder = CommonhausUser.Builder.class)
public class CommonhausUser {
    public static final String MEMBER_ROLE = "member";

    public enum MemberStatus {
        REVOKED,
        SUSPENDED,
        DECLINED,
        COMMITTEE,
        ACTIVE,
        PENDING,
        INACTIVE,
        SPONSOR,
        UNKNOWN;

        public static MemberStatus fromString(String role) {
            if (role == null) {
                return UNKNOWN;
            }
            try {
                return MemberStatus.valueOf(role.toUpperCase());
            } catch (IllegalArgumentException e) {
                return UNKNOWN;
            }
        }

        public boolean mayHaveEmail() {
            return this == COMMITTEE
                    || this == ACTIVE
                    || this == INACTIVE;
        }

        public boolean updateToPending() {
            return this == UNKNOWN
                    || this == SPONSOR
                    || this == INACTIVE;
        }

        public boolean updateFromPending() {
            return this == UNKNOWN
                    || this == PENDING;
        }
    }

    @SuppressWarnings("unused")
    public static class Discord {
        String id;
        String username;
        String discriminator;
        boolean verified;
    }

    public static class ForwardEmail {
        /** Is an alias active for this user */
        @JsonAlias("active")
        boolean configured;

        /** Additional ForwardEmail aliases. Optional and rare. */
        @JsonAlias("alt_alias")
        List<String> altAlias;

        public Collection<? extends String> altAlias() {
            return altAlias == null ? List.of() : altAlias;
        }

        public boolean validAddress(String email, String login, String defaultDomain) {
            return email.equals(login + "@" + defaultDomain) || altAlias().contains(email);
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
    }

    public record AttestationPost(
            @NotNull String id,
            @NotNull String version) {
    }

    public record Attestation(
            @NotNull @JsonAlias("with_status") MemberStatus withStatus,
            @NotNull String date,
            @NotNull String version) {
    }

    public static class Data {
        @NotNull
        MemberStatus status = MemberStatus.UNKNOWN;

        @JsonAlias("good_until")
        GoodStanding goodUntil = new GoodStanding();

        Services services = new Services();
    }

    public record MembershipApplication(
            @NotNull String nodeId,
            @NotNull String htmlUrl) {

        static MembershipApplication fromDataCommonType(DataCommonItem data) {
            return new MembershipApplication(data.id, data.url);
        }
    }

    @NotNull
    final String login;
    @NotNull
    final long id;
    @NotNull
    final Data data;
    @NotNull
    final List<String> history;

    Boolean isMember;
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
    }

    private CommonhausUser(String login, long id) {
        this.login = login;
        this.id = id;
        this.data = new Data();
        this.history = new ArrayList<>();
        this.isMember = null;
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
        String when = DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        history.add("%s %s".formatted(when, message));
    }

    boolean updateMemberStatus(AppContextService ctx, Set<String> roles) {
        MemberStatus oldStatus = data.status;
        if (data.status == MemberStatus.UNKNOWN && !roles.isEmpty()) {
            List<MemberStatus> status = roles.stream()
                    .map(r -> ctx.getStatusForRole(r))
                    .sorted()
                    .toList();
            data.status = status.get(0);
        }
        return oldStatus != data.status;
    }

    public ApiResponse toResponse() {
        return new ApiResponse(ApiResponse.Type.HAUS, data)
                .responseStatus(postConflict() ? Response.Status.CONFLICT : Response.Status.OK);
    }

    @Override
    public String toString() {
        return "CommonhausUser [login=" + login + ", id=" + id + ", sha=" + sha + ", conflict=" + conflict + "]";
    }

    public static CommonhausUser parseFile(ScopedQueryContext qc, GHContent content) throws IOException {
        CommonhausUser user = qc.parseFile(content, CommonhausUser.class);
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

        public MembershipApplication application;
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

        public CommonhausUser build() {
            return new CommonhausUser(this);
        }
    }
}
