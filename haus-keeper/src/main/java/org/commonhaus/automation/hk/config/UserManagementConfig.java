package org.commonhaus.automation.hk.config;

import java.util.HashMap;
import java.util.Map;

import org.commonhaus.automation.config.RepoSource;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class UserManagementConfig {
    public static final UserManagementConfig DISABLED = new UserManagementConfig() {
        @Override
        public boolean isDisabled() {
            return true;
        }
    };

    Boolean enabled;

    String defaultAliasDomain;
    RepoSource attestations;
    GroupRoleConfig groupRole;
    Map<String, String> roleStatus = new HashMap<>();

    public boolean isDisabled() {
        return enabled != null && !enabled;
    }

    public RepoSource attestations() {
        return attestations;
    }

    public Map<String, String> teamRoles() {
        return groupRole == null ? Map.of() : groupRole.teams;
    }

    public Map<String, String> collaboratorRoles() {
        return groupRole == null ? Map.of() : groupRole.outsideCollaborators;
    }

    public Map<String, String> roleStatus() {
        return roleStatus;
    }

    public String defaultAliasDomain() {
        return defaultAliasDomain;
    }

    public boolean emailDisabled() {
        return isDisabled() || defaultAliasDomain == null;
    }

    public record GroupRoleConfig(
            Map<String, String> teams,
            Map<String, String> outsideCollaborators) {
    }
}
