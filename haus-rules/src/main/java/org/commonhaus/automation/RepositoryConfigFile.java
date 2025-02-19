package org.commonhaus.automation;

import org.commonhaus.automation.github.notice.NoticeConfig;
import org.commonhaus.automation.github.voting.VoteConfig;

public class RepositoryConfigFile {
    public static final String NAME = "cf-automation.yml";
    public NoticeConfig notice;
    public VoteConfig voting;

    public static class RepositoryConfig {
        protected Boolean enabled;

        protected RepositoryConfig() {
        }

        public boolean isDisabled() {
            return enabled != null && !enabled;
        }
    }
}
