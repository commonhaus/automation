package org.commonhaus.automation.github.voting;

import java.util.List;
import java.util.Map;

import org.commonhaus.automation.RepositoryConfigFile;
import org.commonhaus.automation.RepositoryConfigFile.RepositoryConfig;

import com.fasterxml.jackson.annotation.JsonAlias;

public class VoteConfig extends RepositoryConfig {
    public static VoteConfig getVotingConfig(RepositoryConfigFile repoConfigFile) {
        if (repoConfigFile == null || repoConfigFile.voting == null) {
            return DISABLED;
        }
        return repoConfigFile.voting;
    }

    // How many votes are required for a vote to count?
    public enum Threshold {
        all,
        majority,
        twothirds,
        fourfifths
    }

    public static class StatusLinks {
        public String badge;
        public String page;
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
    public Map<String, Threshold> voteThreshold;

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
}
