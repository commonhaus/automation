package org.commonhaus.automation.hk.config;

import java.util.Set;

import org.commonhaus.automation.config.EmailNotification;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Configuration object for project-mail-aliases.yml
 */
@RegisterForReflection
public record ProjectAliasMapping(
        String domain,
        Set<String> domains,
        Set<UserAliasList> userMapping,
        EmailNotification emailNotifications) {

    public static final String CONFIG_FILE = "project-mail-aliases.yml";

    public ProjectAliasMapping {
        if (domain != null && (domains == null || domains.isEmpty())) {
            domains = Set.of(domain);
        }
        domain = null; // Always set to null after migration to domains
    }

    /**
     * User alias mapping within ProjectMailConfig
     */
    public record UserAliasList(
            String login,
            Set<String> aliases) {

        public boolean isValid(Set<String> domains) {
            return login != null && !login.isEmpty()
                    && aliases != null && !aliases.isEmpty()
                    && aliases.stream().allMatch(a -> {
                        if (a == null) {
                            return false;
                        }
                        String domain = a.substring(a.indexOf("@") + 1);
                        return domains.contains(domain);
                    });
        }

        @Override
        public String toString() {
            return "UserAlias[login=%s, aliases=%s]".formatted(login, aliases);
        }
    }

    public boolean hasDomains() {
        return domains != null && !domains.isEmpty();
    }

    public boolean hasUserMapping() {
        return userMapping != null && !userMapping.isEmpty();
    }

    public boolean isEnabled() {
        return hasDomains() && hasUserMapping();
    }

    /**
     * @return the emailNotifications
     */
    public EmailNotification emailNotifications() {
        return emailNotifications == null
                ? EmailNotification.UNDEFINED
                : emailNotifications;
    }

    @Override
    public String toString() {
        return "ProjectMailConfig[domains=%s, userMapping=%s]".formatted(
                domains, userMapping);
    }
}
