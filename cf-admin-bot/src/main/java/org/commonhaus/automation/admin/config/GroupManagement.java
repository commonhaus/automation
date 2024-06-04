package org.commonhaus.automation.admin.config;

import java.util.List;
import java.util.Objects;

import org.commonhaus.automation.admin.config.RepositoryConfigFile.RepositoryConfig;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class GroupManagement extends RepositoryConfig {

    public static final GroupManagement DISABLED = new GroupManagement() {
        @Override
        public boolean isDisabled() {
            return true;
        }
    };

    public static GroupManagement getGroupManagementConfig(RepositoryConfigFile repoConfigFile) {
        if (repoConfigFile == null) {
            return DISABLED;
        }
        return repoConfigFile.groupManagement();
    }

    public List<SourceConfig> sources;

    GroupManagement() {
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

    public List<SourceConfig> sources() {
        return sources == null ? List.of() : sources;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this)
            return true;
        if (obj == null || obj.getClass() != this.getClass())
            return false;
        var that = (GroupManagement) obj;
        return Objects.equals(this.sources, that.sources) &&
                Objects.equals(this.enabled, that.enabled);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sources, enabled);
    }
}
