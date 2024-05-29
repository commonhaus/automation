package org.commonhaus.automation.admin.api;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.commonhaus.automation.admin.github.AdminQueryContext;
import org.kohsuke.github.GHContent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import io.smallrye.common.constraint.NotNull;

/**
 * Commonhaus user: stored as json file
 */
@JsonDeserialize(builder = CommonhausUser.Builder.class)
public class CommonhausUser {

    public enum MemberStatus {
        COMMITTEE,
        ACTIVE,
        PENDING,
        INACTIVE,
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
    }

    public static class Discord {
        String id;
        String username;
        String discriminator;
        boolean verified;
    }

    public static class ForwardEmail {
        /** Is an alias active for this user */
        boolean active;

        /** Additional ForwardEmail aliases. Optional and rare. */
        @JsonProperty("alt_alias")
        List<String> altAlias;

        public Collection<? extends String> altAlias() {
            return altAlias == null ? List.of() : altAlias;
        }

        public boolean validAddress(String email, String login, String defaultDomain) {
            return email.equals(login + "@" + defaultDomain) || altAlias().contains(email);
        }
    }

    public static class Services {
        @JsonProperty("forward_email")
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

    public static class GoodStanding {
        Map<String, Attestation> attestation = new HashMap<>();
        String contribution;
        String dues;

        @JsonIgnore
        public boolean isValid() {
            return (attestation.isEmpty()
                    || attestation.values().stream().allMatch(s -> s.date.matches("\\d{4}-\\d{2}-\\d{2}")))
                    && (contribution == null || contribution.matches("\\d{4}-\\d{2}-\\d{2}"))
                    && (dues == null || dues.matches("\\d{4}-\\d{2}-\\d{2}"));
        }
    }

    public record AttestationPost(
            @NotNull String id,
            @NotNull String version) {
    }

    public record Attestation(
            @NotNull @JsonProperty("with_status") MemberStatus withStatus,
            @NotNull String date,
            @NotNull String version) {
    }

    public static class Data {
        @NotNull
        MemberStatus status = MemberStatus.UNKNOWN;

        @JsonProperty("good_until")
        GoodStanding goodUntil = new GoodStanding();

        Services services = new Services();
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

    public boolean fetched() {
        return sha != null;
    }

    public String sha() {
        return sha;
    }

    public MemberStatus status() {
        return data.status;
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

    public void appendAttestation(MemberStatus userStatus, AttestationPost post) {
        LocalDate date = LocalDate.now().plusYears(1);

        Attestation attestation = new Attestation(
                userStatus,
                date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                post.version());

        data.goodUntil.attestation.put(post.id, attestation);
    }

    public void updateStatus(List<MemberStatus> roleStatus) {
        for (MemberStatus newStatus : roleStatus) {
            if (newStatus.ordinal() < data.status.ordinal()) {
                data.status = newStatus;
            }
        }
    }

    @Override
    public String toString() {
        return "CommonhausUser [login=" + login + ", id=" + id + ", sha=" + sha + ", conflict=" + conflict + "]";
    }

    public static CommonhausUser parseFile(AdminQueryContext qc, GHContent content) throws IOException {
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
