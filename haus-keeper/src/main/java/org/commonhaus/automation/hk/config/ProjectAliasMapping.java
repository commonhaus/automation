package org.commonhaus.automation.hk.config;

import java.util.List;

import org.commonhaus.automation.config.EmailNotification;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Configuration object for project-mail-aliases.yml
 */
@RegisterForReflection
public record ProjectAliasMapping(
        String domain,
        List<UserAliasList> userMapping,
        EmailNotification emailNotifications) {

    public static final String CONFIG_FILE = "project-mail-aliases.yml";

    /**
     * User alias mapping within ProjectMailConfig
     */
    public record UserAliasList(
            String login,
            List<String> aliases) {

        public boolean isValid(String projectDomain) {
            return login != null && !login.isEmpty()
                    && aliases != null && !aliases.isEmpty()
                    && aliases.stream().allMatch(a -> a != null && a.endsWith("@" + projectDomain));
        }

        @Override
        public String toString() {
            return String.format("UserAlias[login=%s, aliases=%s]", login, aliases);
        }
    }

    public boolean isEnabled() {
        return domain != null && !domain.isEmpty() && userMapping != null && !userMapping.isEmpty();
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
        return String.format("ProjectMailConfig[domain=%s, userMapping=%s]",
                domain, userMapping);
    }
}
