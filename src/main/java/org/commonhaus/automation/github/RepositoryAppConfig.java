package org.commonhaus.automation.github;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.commonhaus.automation.github.actions.Action;
import org.commonhaus.automation.github.rules.Rule;

public class RepositoryAppConfig {
    static final String NAME = "cf-automation.yml";

    static class File {
        public Notice.Config notice = new Notice.Config();
        public Voting.Config voting = new Voting.Config();
    }

    public static class CommonConfig {
        protected Boolean enabled;

        public Map<String, Action> actions = new HashMap<>();

        CommonConfig() {
        }

        public boolean isEnabled() {
            return (enabled == null || enabled.booleanValue()) && !actions.isEmpty();
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
