package org.commonhaus.automation.admin.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class TeamManagementConfig extends RepositoryConfig {

    public static final TeamManagementConfig DISABLED = new TeamManagementConfig() {
        @Override
        public boolean isDisabled() {
            return true;
        }
    };

    public static TeamManagementConfig getGroupManagementConfig(AdminConfigFile repoConfigFile) {
        if (repoConfigFile == null) {
            return DISABLED;
        }
        return repoConfigFile.groupManagement();
    }

    public final List<TeamSourceConfig> sources = new ArrayList<>();

    TeamManagementConfig() {
    }

    @Override
    public boolean isDisabled() {
        return super.isDisabled() || sources == null || sources.isEmpty();
    }

    @Override
    public String toString() {
        return "GroupManagement{sources=%s, enabled=%s}"
                .formatted(sources, enabled);
    }

    public List<TeamSourceConfig> sources() {
        return sources;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this)
            return true;
        if (obj == null || obj.getClass() != this.getClass())
            return false;
        var that = (TeamManagementConfig) obj;
        return Objects.equals(this.sources, that.sources) &&
                Objects.equals(this.enabled, that.enabled);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sources, enabled);
    }
}
