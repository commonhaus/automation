package org.commonhaus.automation.admin.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.commonhaus.automation.admin.github.AdminQueryContext;
import org.commonhaus.automation.admin.github.AppContextService;
import org.kohsuke.github.GHContent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import io.smallrye.common.constraint.NotNull;

/**
 * Commonhaus user: stored as json file
 */
@JsonDeserialize(builder = CommonhausUser.Builder.class)
public class CommonhausUser {

    public enum MemberStatus {
        ACTIVE,
        INACTIVE,
        COMMITTEE,
        PENDING,
        REVOKED,
        SPONSOR,
        SUSPENDED,
        UNKNOWN
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
    }

    public static class Services {
        @JsonProperty("forward_email")
        ForwardEmail forwardEmail;
        Discord discord;
    }

    public static class GoodStanding {
        String attestationUntil;
        String contributionUntil;
        String duesUntil;
    }

    public record Attestation(
            @NotNull @JsonProperty("with_status") MemberStatus withStatus,
            @NotNull String YMD,
            @NotNull String id,
            @NotNull String version,
            @NotNull JsonNode data) {

        public boolean isValid(AppContextService ctx) {
            return withStatus != null
                    && YMD != null
                    && data != null
                    && YMD.matches("\\d{4}-\\d{2}-\\d{2}")
                    && (ctx.validAttestation(id));
        }
    }

    public static class Data {
        @NotNull
        MemberStatus status = MemberStatus.UNKNOWN;

        @JsonProperty("good_until")
        GoodStanding goodUntil = new GoodStanding();

        Services services = new Services();

        @NotNull
        List<Attestation> attestations = new ArrayList<>();
    }

    @NotNull
    final String login;
    @NotNull
    final long id;
    @NotNull
    final Data data;

    transient String sha = null;

    CommonhausUser(String login, long id, Data data) {
        this.login = login;
        this.id = id;
        this.data = data;
    }

    CommonhausUser(String login, long id) {
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
        return data.services;
    }

    public List<Attestation> attestations() {
        return data.attestations;
    }

    public boolean fetched() {
        return sha != null;
    }

    public String sha() {
        return sha;
    }

    public void append(Attestation attestation) {
        data.attestations.add(attestation);
    }

    public static CommonhausUser parseFile(AdminQueryContext qc, GHContent content) throws IOException {
        CommonhausUser user = qc.parseFile(content, CommonhausUser.class);
        if (user != null) {
            user.sha = content.getSha();
        }
        return user;
    }

    public static CommonhausUser create(String login, long id, MemberStatus status) {
        CommonhausUser user = new CommonhausUser(login, id);
        user.data.status = status;
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
