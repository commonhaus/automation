package org.commonhaus.automation.admin.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.commonhaus.automation.admin.github.InstallationAccess;
import org.commonhaus.automation.github.context.QueryContext;

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

    public SponsorsConfig sponsors;
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

    /**
     * Link the installation to repositories and organizations that
     * used by this configuration. It requires read access to the
     * source file, and write access to orgs containing target teams.
     *
     * @param access the installation access object
     */
    public void setAccess(InstallationAccess access) {
        sources.forEach(source -> {
            // we should have (at least) read access to the source repo
            access.add(source.repo());
            if (source.performSync()) {
                // If we have enough information to perform a sync,
                // we should have write access for all of the target teams
                // (which includes the organization, based on bot permissions)
                source.sync().values().forEach(sync -> {
                    sync.teams().forEach(team -> {
                        access.add(QueryContext.toOrganizationName(team));
                    });
                });
            }
        });
        if (sponsors != null && sponsors.repository() != null) {
            access.add(sponsors.repository());
        }
    }
}
