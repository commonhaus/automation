package org.commonhaus.automation.admin.github;

import java.util.HashSet;
import java.util.Set;

public class InstallationAccess {
    final long installationId;
    final Set<String> write = new HashSet<>();
    final Set<String> read = new HashSet<>();

    InstallationAccess(long installationId) {
        this.installationId = installationId;
    }

    public long installationId() {
        return installationId;
    }

    public void addWrite(String scope) {
        write.add(scope);
    }

    public void addRead(String scope) {
        read.add(scope);
    }

    public boolean containsRepo(String scope) {
        return write.contains(scope) || read.contains(scope);
    }
}
