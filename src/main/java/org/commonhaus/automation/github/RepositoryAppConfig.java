package org.commonhaus.automation.github;

import java.util.Iterator;
import java.util.List;

import org.commonhaus.automation.github.rules.Rule;

public class RepositoryAppConfig {
    public static final String NAME = "cf-automation.yml";

    public static class File {
        public final Notice.Config notice = new Notice.Config();
        public final Voting.Config voting = new Voting.Config();
    }

    public static class CommonConfig {
        protected Boolean enabled;

        CommonConfig() {
        }

        public boolean isDisabled() {
            return enabled != null && !enabled;
        }
    }

    public static class DiscussionConfig {
        public List<Rule> rules;
    }

    public static class PullRequestConfig {
        public List<Rule> rules;
    }

    public static <T> Iterable<T> toIterable(Iterator<T> iterator) {
        return () -> iterator;
    }
}
