package org.commonhaus.automation.hk.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.annotation.Nonnull;

import com.fasterxml.jackson.annotation.JsonAlias;

public class CommonhausUserData {
    @Nonnull
    MemberStatus status = MemberStatus.UNKNOWN;

    @JsonAlias("good_until")
    GoodStanding goodUntil = new GoodStanding();

    Services services = new Services();

    List<String> projects = new ArrayList<>();

    public List<String> projects() {
        return projects;
    }

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
        Set<String> altAlias;

        public boolean hasDefaultAlias() {
            return hasDefaultAlias;
        }

        public void enableDefaultAlias() {
            hasDefaultAlias = true;
        }

        public Collection<String> altAlias() {
            if (altAlias == null) {
                altAlias = new HashSet<>();
            }
            return altAlias;
        }

        public void addAliases(List<String> validAliases) {
            altAlias().addAll(validAliases);
        }
    }

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
