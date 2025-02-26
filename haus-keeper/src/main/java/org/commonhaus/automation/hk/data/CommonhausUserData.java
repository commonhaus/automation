package org.commonhaus.automation.hk.data;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.Nonnull;

import com.fasterxml.jackson.annotation.JsonAlias;

public class CommonhausUserData {
    @Nonnull
    MemberStatus status = MemberStatus.UNKNOWN;

    @JsonAlias("good_until")
    GoodStanding goodUntil = new GoodStanding();

    Services services = new Services();

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

        /** Additional ForwardEmail aliases. Optional. */
        @JsonAlias("alt_alias")
        List<String> altAlias;

        public boolean hasDefaultAlias() {
            return hasDefaultAlias;
        }

        public void enableDefaultAlias() {
            hasDefaultAlias = true;
        }

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

    public record Attestation(
            @Nonnull @JsonAlias("with_status") MemberStatus withStatus,
            @Nonnull String date,
            @Nonnull String version) {
    }
}
