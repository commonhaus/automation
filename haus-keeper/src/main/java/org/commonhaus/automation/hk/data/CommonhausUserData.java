package org.commonhaus.automation.hk.data;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import jakarta.annotation.Nonnull;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

public class CommonhausUserData {
    @Nonnull
    MemberStatus status = MemberStatus.UNKNOWN;

    @JsonAlias("good_until")
    GoodStanding goodUntil = new GoodStanding();

    Services services = new Services();

    @JsonDeserialize(as = TreeSet.class)
    Set<String> projects = new TreeSet<>();

    public Collection<String> projects() {
        return projects;
    }

    public static class Discord {
        String id;
        String username;
        String discriminator;
        boolean verified;

        public void merge(Discord discord) {
            if (discord == null) {
                return;
            }
            this.id = discord.id;
            this.username = discord.username;
            this.discriminator = discord.discriminator;
            this.verified = discord.verified;
        }
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

        public void addAliases(Collection<String> validAliases) {
            altAlias().addAll(validAliases);
        }

        public void merge(ForwardEmail forwardEmail) {
            if (forwardEmail == null) {
                return;
            }
            this.hasDefaultAlias = forwardEmail.hasDefaultAlias;
            if (forwardEmail.altAlias != null) {
                this.altAlias().addAll(forwardEmail.altAlias);
            }
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

        public void merge(Services other) {
            if (other == null) {
                return;
            }
            if (other.forwardEmail != null) {
                this.forwardEmail().merge(other.forwardEmail);
            }
            if (other.discord != null) {
                this.discord().merge(other.discord);
            }
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

        public void merge(GoodStanding other) {
            if (other == null) {
                return;
            }
            other.attestation.forEach((key, value) -> {
                attestation.put(key, value);
            });
            contribution = other.contribution;
            dues = other.dues;
        }
    }

    public record Attestation(
            @Nonnull @JsonAlias("with_status") MemberStatus withStatus,
            @Nonnull String date,
            @Nonnull String version) {
    }

    public void merge(CommonhausUserData other) {
        if (other == null) {
            return;
        }
        this.status = other.status;
        this.goodUntil.merge(other.goodUntil);
        this.services.merge(other.services);
        this.projects().addAll(other.projects());
    }
}
