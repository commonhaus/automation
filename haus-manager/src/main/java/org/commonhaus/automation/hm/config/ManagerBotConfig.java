package org.commonhaus.automation.hm.config;

import static org.commonhaus.automation.github.context.GitHubQueryContext.toFullName;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;

// Specified in application.yaml
@ConfigMapping(prefix = "automation.hausManager")
public interface ManagerBotConfig {

    /** GitHub organization for configuration */
    HomeConfig home();

    SchedulerConfig cron();

    Optional<NamecheapConfig> namecheap();

    interface HomeConfig {

        String organization();

        String repository();

        default String repositoryFullName() {
            if (repository().contains("/")) {
                return repository();
            }
            return toFullName(organization(), repository());
        }
    }

    interface SchedulerConfig {

        // Cron expression for periodic sync of domains
        Optional<String> domain();

        // Cron expression for periodic sync of sponsors
        Optional<String> sponsor();

        // Cron expression for periodic sync of members
        Optional<String> projects();

        // Cron expression for periodic sync of members
        Optional<String> organization();
    }

    interface NamecheapConfig {
        String url();

        String username();

        String apiKey();

        String ipv4Addr();

        /** Repository for domain list workflow dispatch */
        String workflowRepository();

        /** Workflow name for domain list updates */
        String workflowName();
    }
}
