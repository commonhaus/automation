package org.commonhaus.automation.github;

import java.util.Iterator;

public class RepositoryAppConfig {
    static final String NAME = "cf-automation.yml";

    static class File {
        public Notice.Config notice = new Notice.Config();
        public Voting.Config voting = new Voting.Config();
    }

    public static class ConfigToggle {
        protected Boolean enabled;

        ConfigToggle() {
        }

        public boolean isEnabled() {
            return enabled == null || enabled.booleanValue();
        }
    }

    public static <T> Iterable<T> toIterable(Iterator<T> iterator) {
        return () -> iterator;
    }
}
