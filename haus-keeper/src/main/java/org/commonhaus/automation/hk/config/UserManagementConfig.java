package org.commonhaus.automation.hk.config;

import java.util.HashMap;
import java.util.Map;

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

    AttestationConfig attestations;
    GroupRoleConfig groupRole;
    Map<String, String> roleStatus = new HashMap<>();

    String defaultAliasDomain;

    public boolean isDisabled() {
        return enabled != null && !enabled;
    }

    public AttestationConfig attestations() {
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

    public record AttestationConfig(
            String path,
            String repo) {
        public String path() {
            return path;
        }

        public String repo() {
            return repo;
        }
    }

    public record GroupRoleConfig(
            Map<String, String> teams,
            Map<String, String> outsideCollaborators) {
    }
}
