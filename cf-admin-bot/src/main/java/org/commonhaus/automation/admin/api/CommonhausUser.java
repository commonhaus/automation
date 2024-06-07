package org.commonhaus.automation.admin.api;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.ws.rs.core.Response;

import org.commonhaus.automation.admin.github.AppContextService;
import org.commonhaus.automation.admin.github.ScopedQueryContext;
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

    public enum MemberStatus {
        COMMITTEE,
        ACTIVE,
        PENDING,
        INACTIVE,
        DENIED,
        REVOKED,
        SUSPENDED,
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
            return this != PENDING
                    && this != REVOKED
                    && this != SPONSOR
                    && this != SUSPENDED;
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

        String applicationId;
    }

    @NotNull
    final String login;
    @NotNull
    final long id;
    @NotNull
    final Data data;

    transient String sha = null;
    transient boolean conflict = false;

    private CommonhausUser(String login, long id, Data data) {
        this.login = login;
        this.id = id;
        this.data = data;
    }

    private CommonhausUser(String login, long id) {
        this.login = login;
        this.id = id;
        this.data = new Data();
    }

    public String login() {
        return login;
    }

    public long id() {
        return id;
    }

    public Services services() {
        return data.services == null ? new Services() : data.services;
    }

    public String sha() {
        return sha;
    }

    public MemberStatus status() {
        return data.status;
    }

    public void sha(String sha) {
        this.sha = sha;
    }

    public GoodStanding goodUntil() {
        return data.goodUntil;
    }

    public boolean postConflict() {
        return conflict;
    }

    public void setConflict(boolean conflict) {
        this.conflict = conflict;
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

        public CommonhausUser build() {
            return new CommonhausUser(login, id, data);
        }
    }
}
