package org.commonhaus.automation.github;

import org.commonhaus.automation.github.RepositoryAppConfig.ConfigToggle;

/**
 * Highlevel workflow to manage voting.
 *
 * This acts as a mixin: stored with CFGH RepoInfo if voting is enabled.
 */
public class Voting {
    public static final String VOTE_DONE = "vote/done";
    public static final String VOTE_OPEN = "vote/open";
    public static final String VOTE_PROCEED = "vote/proceed";
    public static final String VOTE_QUORUM = "vote/quorum";
    public static final String VOTE_REVISE = "vote/revise";

    static Voting.Config getVotingConfig(RepositoryAppConfig.File repoConfigFile) {
        if (repoConfigFile == null || repoConfigFile.voting == null) {
            return Voting.Config.DISABLED;
        }
        return repoConfigFile.voting;
    }

    public static class Config extends ConfigToggle {
        public static final Config DISABLED = new Config() {
            @Override
            public boolean isEnabled() {
                return false;
            }
        };
    }
}
