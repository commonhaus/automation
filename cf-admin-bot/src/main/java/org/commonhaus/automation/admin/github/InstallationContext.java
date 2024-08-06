package org.commonhaus.automation.admin.github;

import java.util.HashSet;
import java.util.Set;

import org.commonhaus.automation.github.context.QueryContext;

/**
 * We have to reach across installations to keep team members in sync, and work
 * between the user-logged-in session and the datastore repository.
 *
 * This class is used to keep track of the installations, the org it is associated with,
 * and the repositories that are accessible to the installation.
 * The bot is installed requires a certain set of permissions, which means it will be
 * able to read/write any repository that is associated with its own org.
 */
public class InstallationContext {
    final long installationId;
    final String orgName;
    final Set<String> write = new HashSet<>();
    final Set<String> read = new HashSet<>();

    InstallationContext(long installationId, String orgName) {
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
