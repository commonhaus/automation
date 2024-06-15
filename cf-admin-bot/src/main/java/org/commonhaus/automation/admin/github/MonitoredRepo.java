package org.commonhaus.automation.admin.github;

import java.util.List;
import java.util.Objects;

import org.commonhaus.automation.admin.config.AdminConfigFile;
import org.commonhaus.automation.admin.config.SponsorsConfig;
import org.commonhaus.automation.admin.config.TeamManagementConfig;
import org.commonhaus.automation.admin.config.TeamSourceConfig;

public final class MonitoredRepo {
    final String repoFullName;
    private final long installationId;
    SponsorsConfig sponsors;
    List<TeamSourceConfig> sourceConfig;
    private String[] dryRunEmailAddress;
    private String[] errorEmailAddress;

    MonitoredRepo(String repoFullName, long installationId) {
        this.repoFullName = repoFullName;
        this.installationId = installationId;
    }

    MonitoredRepo refresh(AdminConfigFile file) {
        TeamManagementConfig groupManagement = TeamManagementConfig.getGroupManagementConfig(file);
        this.sponsors = groupManagement.sponsors;
        this.sourceConfig = groupManagement.sources();
        this.dryRunEmailAddress = file.emailAddress() == null ? null : file.emailAddress().dryRun();
        this.errorEmailAddress = file.emailAddress() == null ? null : file.emailAddress().errors();
        return this;
    }

    @Override
    public String toString() {
        return "RepoConfig{sourceConfig=%s}".formatted(sourceConfig);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof MonitoredRepo that))
            return false;
        return installationId == that.installationId && repoFullName.equals(that.repoFullName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(repoFullName, installationId);
    }

    public String repoFullName() {
        return repoFullName;
    }

    public SponsorsConfig sponsors() {
        return sponsors;
    }

    public List<TeamSourceConfig> sourceConfig() {
        return sourceConfig;
    }

    public String[] dryRunEmailAddress() {
        return dryRunEmailAddress;
    }

    public String[] errorEmailAddress() {
        return errorEmailAddress;
    }

    public long installationId() {
        return installationId;
    }
}
