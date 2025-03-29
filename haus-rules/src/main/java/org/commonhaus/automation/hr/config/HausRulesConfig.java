package org.commonhaus.automation.hr.config;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record HausRulesConfig(
        NoticeConfig notice,
        VoteConfig voting) {

    public static final String NAME = "cf-haus-rules.yml";
    public static final String PATH = ".github/" + NAME;

    public static class RepositoryConfig {
        protected Boolean enabled;

        protected RepositoryConfig() {
        }

        public boolean isDisabled() {
            return enabled != null && !enabled;
        }
    }
}
