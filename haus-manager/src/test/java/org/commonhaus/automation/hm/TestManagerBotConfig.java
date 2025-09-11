package org.commonhaus.automation.hm;

import java.util.Optional;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import org.commonhaus.automation.github.context.ContextHelper.DefaultValues;
import org.commonhaus.automation.github.context.ContextHelper.Resource;
import org.commonhaus.automation.hm.config.ManagerBotConfig;

@ApplicationScoped
@Alternative
@Priority(1)
class TestManagerBotConfig implements ManagerBotConfig {

    static final DefaultValues DEFAULT = new DefaultValues(
            46053716,
            new Resource(144493209, "test-org"),
            new Resource("test-org/test-repo"));

    DefaultValues activeConfig = DEFAULT;

    public void setConfig(DefaultValues config) {
        activeConfig = config;
    }

    public void reset() {
        // Reset the configuration
        activeConfig = DEFAULT;
    }

    @Override
    public HomeConfig home() {
        return new HomeConfig() {

            @Override
            public String organization() {
                return activeConfig.orgName();
            }

            @Override
            public String repository() {
                return activeConfig.repoFullName();
            }
        };
    }

    @Override
    public SchedulerConfig cron() {
        return new SchedulerConfig() {

            @Override
            public Optional<String> sponsor() {
                return Optional.empty();
            }

            @Override
            public Optional<String> projects() {
                return Optional.empty();
            }

            @Override
            public Optional<String> organization() {
                return Optional.empty();
            }

            @Override
            public Optional<String> domain() {
                return Optional.empty();
            }
        };
    }

    @Override
    public Optional<NamecheapConfig> namecheap() {
        return Optional.empty();
    }
}
