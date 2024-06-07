package org.commonhaus.automation.admin.config;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class UserManagementConfig extends RepositoryConfig {

    public static final UserManagementConfig DISABLED = new UserManagementConfig() {
        @Override
        public boolean isDisabled() {
            return true;
        }

        public boolean emailDisabled() {
            return true;
        }
    };

    public static UserManagementConfig getUserManagementConfig(AdminConfigFile repoConfigFile) {
        if (repoConfigFile == null) {
            return DISABLED;
        }
        return repoConfigFile.userManagement();
    }

    AttestationConfig attestations;
    GroupRoleConfig groupRole;
    Map<String, String> roleStatus = new HashMap<>();
    String defaultAliasDomain;

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

    public static record AttestationConfig(
            String path,
            String repo) {
        public String path() {
            return path;
        }

        public String repo() {
            return repo;
        }
    }

    public static record GroupRoleConfig(
            Map<String, String> teams,
            Map<String, String> outsideCollaborators) {
    }
}
