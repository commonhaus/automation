package org.commonhaus.automation.admin.github;

import java.util.HashSet;
import java.util.Set;

import org.commonhaus.automation.github.context.QueryContext;

public class InstallationAccess {
    final long installationId;
    final String orgName;
    final Set<String> write = new HashSet<>();
    final Set<String> read = new HashSet<>();

    InstallationAccess(long installationId, String orgName) {
        this.installationId = installationId;
        this.orgName = orgName;
    }

    public long installationId() {
        return installationId;
    }

    public void add(String scope) {
        String toOrg = QueryContext.toOrganizationName(scope);
        if (toOrg.equals(orgName)) {
            write.add(scope);
        } else {
            read.add(scope);
        }
    }

    public boolean containsRepo(String scope) {
        return write.contains(scope) || read.contains(scope);
    }
}
