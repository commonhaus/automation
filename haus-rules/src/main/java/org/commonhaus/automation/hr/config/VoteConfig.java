package org.commonhaus.automation.hr.config;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.commonhaus.automation.hr.config.HausRulesConfig.RepositoryConfig;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

public class VoteConfig extends RepositoryConfig {
    // How many votes are required for a vote to count?
    public enum Threshold {
        all,
        majority,
        twothirds("2/3"),
        fourfifths("4/5");

        final String label;

        Threshold() {
            this.label = name();
        }

        Threshold(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public int requiredVotes(int groupSize) {
            // Always round up: whole people vote, not fractions
            return switch (this) {
                case all -> groupSize;
                case majority -> (groupSize + 1) / 2;
                case twothirds -> (int) Math.ceil(groupSize * 2.0 / 3.0);
                case fourfifths -> (int) Math.ceil(groupSize * 4.0 / 5.0);
            };
        }

        public static Threshold fromString(String group) {
            for (Threshold t : values()) {
                if (t.name().equalsIgnoreCase(group) || t.label.equalsIgnoreCase(group)) {
                    return t;
                }
            }
            return all;
        }
    }

    public record StatusLinks(
            String badge,
            String page) {
    }

    public record TeamMapping(
            String data,
            String team) {
        boolean valid() {
            return data != null && team != null;
        }
    }

    public record AlternateDefinition(
            String field,
            TeamMapping primary,
            TeamMapping secondary) {
        boolean valid() {
            return field != null
                    && primary != null && primary.valid()
                    && secondary != null && secondary.valid();
        }
    }

    public record AlternateConfig(
            String source,
            String repo,
            List<AlternateDefinition> mapping) {

        public boolean valid() {
            return source != null
                    && repo != null
                    && mapping != null
                    && !mapping.isEmpty()
                    && mapping.stream().allMatch(AlternateDefinition::valid);
        }
    }

    public static final VoteConfig DISABLED = new VoteConfig() {
        @Override
        public boolean isDisabled() {
            return true;
        }
    };

    /**
     * List of logins that can provide manual vote results
     * to close/finish a vote.
     */
    @JsonAlias("managers")
    public List<String> managers;

    /**
     * List of logins to exclude from vote results
     */
    @JsonAlias("exclude_login")
    public List<String> excludeLogin;

    /**
     * Email addresses to send error notifications to.
     */
    @JsonAlias("error_email_address")
    public String[] errorEmailAddress;

    /**
     * Map of voting group to required threshold to reach quorum for electronic participation.
     */
    @JsonAlias("vote_threshold")
    @JsonDeserialize(contentUsing = ThresholdDeserializer.class)
    public Map<String, Threshold> voteThreshold;

    /**
     * Configuration for alternate representatives.
     */
    public List<AlternateConfig> alternates;

    /**
     * Link templates for status badges and pages.
     */
    public StatusLinks status;

    public boolean sendErrorEmail() {
        return errorEmailAddress != null && errorEmailAddress.length > 0;
    }

    public Threshold votingThreshold(String group) {
        if (voteThreshold == null) {
            return Threshold.all;
        }
        return voteThreshold.getOrDefault(group, Threshold.all);
    }

    public boolean isMemberExcluded(String login) {
        if (excludeLogin == null || excludeLogin.isEmpty()) {
            return false;
        }
        return excludeLogin.contains(login);
    }

    public static class ThresholdDeserializer extends StdDeserializer<Threshold> {
        public ThresholdDeserializer() {
            this(null);
        }

        public ThresholdDeserializer(Class<Threshold> vc) {
            super(vc);
        }

        @Override
        public Threshold deserialize(JsonParser jp, DeserializationContext context) throws IOException {
            ObjectMapper mapper = (ObjectMapper) jp.getCodec();
            JsonNode root = mapper.readTree(jp);
            return Threshold.fromString(root.asText());
        }
    }
}
